/*
 * Copyright 2026 Joao Silva
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jpmsilva.jsystemd;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link Systemd}.
 *
 * @author Joao Silva
 */
class SystemdTest {

  private static final Duration PERIOD = Duration.ofMillis(10);

  private MockedStatic<SystemdNotify> notify;
  private MockedStatic<SystemdUtilities> utilities;
  private Systemd systemd;

  @BeforeEach
  void setUp() {
    notify = Mockito.mockStatic(SystemdNotify.class);
    utilities = Mockito.mockStatic(SystemdUtilities.class, Mockito.CALLS_REAL_METHODS);
    // each test acts as if it were a process of its own, with an instance of its own
    Systemd.resetProcess();
    systemd = Systemd.instance();
  }

  @AfterEach
  void tearDown() throws Exception {
    systemd.close();
    utilities.close();
    notify.close();
  }

  private static StatusProvider provider(String status) {
    return new StatusProvider() {
      @Override
      public @NonNull String status() {
        return status;
      }
    };
  }

  // status providers that report in some states only, as their status method has a default, which keeps them from being written as lambdas
  private static final class Starting implements StartingStatusProvider {

    @Override
    public @NonNull String status() {
      return "starting";
    }
  }

  // counts the calls, so that the tests can tell that it is not asked for its status in the other states
  private record Running(AtomicInteger calls) implements RunningStatusProvider {

    @Override
    public @NonNull String status() {
      calls.incrementAndGet();
      return "running";
    }
  }

  private static final class Stopping implements StoppingStatusProvider {

    @Override
    public @NonNull String status() {
      return "stopping";
    }
  }

  private static final class StartingAndStopping implements StartingStatusProvider, StoppingStatusProvider {

    @Override
    public @NonNull String status() {
      return "starting and stopping";
    }
  }

  @Test
  void doesNotSendStatusWithoutProviders() {
    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status(anyString()), never());
  }

  @Test
  void notifiesReadyOnlyOnce() {
    systemd.ready();
    systemd.ready();

    notify.verify(SystemdNotify::ready, Mockito.times(1));
    assertThat(systemd.isReady()).isTrue();
  }

  @Test
  void doesNotExtendTheTimeoutOnceReady() {
    systemd.extendTimeout();
    systemd.ready();
    systemd.extendTimeout();

    // the timeout is not extended while the application runs, as systemd would extend RuntimeMaxSec
    notify.verify(() -> SystemdNotify.extendTimeout(Mockito.anyLong()), Mockito.times(1));
  }

  @Test
  void ignoresReadyWhenStopping() {
    systemd.stopping();

    systemd.ready();

    notify.verify(SystemdNotify::ready, never());
    // the startup is over, even if the application never became ready
    assertThat(systemd.isReady()).isTrue();
  }

  @Test
  void extendsTheTimeoutWhileStopping() {
    systemd.ready();
    systemd.stopping();

    systemd.extendTimeout();

    // systemd extends TimeoutStopSec
    notify.verify(() -> SystemdNotify.extendTimeout(MICROSECONDS.convert(29, SECONDS)));
  }

  @Test
  void doesNotSendStatusWhenReadyWithoutProviders() {
    systemd.ready();

    notify.verify(SystemdNotify::ready);
    notify.verify(() -> SystemdNotify.status(anyString()), never());
  }

  @Test
  void sendsStatusFromProviders() {
    systemd.addStatusProviders(provider("first"), provider(""), provider("second"));

    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status("first, second"));
  }

  @Test
  void doesNotReplaceTheStatusWithAnOlderOne() {
    AtomicBoolean first = new AtomicBoolean(true);
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        if (first.getAndSet(false)) {
          // a slow status provider, while another update, such as a periodic one, starts and completes on another thread
          completeOnAnotherThread(systemd::updateStatus);
          return "older";
        }
        return "newer";
      }
    });

    systemd.updateStatus();

    // the newer status was sent on the other thread, where the static mock of SystemdNotify does not apply
    assertThat(systemd.lastStatusUpdateSent()).isEqualTo(2);
    notify.verify(() -> SystemdNotify.status("older"), never());
  }

  @Test
  void sendsEachStatusUpdatedInTurn() {
    systemd.addStatusProviders(provider("status"));

    systemd.updateStatus();
    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status("status"), Mockito.times(2));
    assertThat(systemd.lastStatusUpdateSent()).isEqualTo(2);
  }

  private static void completeOnAnotherThread(Runnable runnable) {
    Thread thread = new Thread(runnable);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void asksProvidersForTheirStatusOnlyInTheStatesTheyReportIn() {
    AtomicInteger runningCalls = new AtomicInteger();
    systemd.addStatusProviders(provider("always"), new Starting(), new Running(runningCalls), new Stopping(), new StartingAndStopping());

    systemd.updateStatus();
    // which sends the status as well
    systemd.ready();
    systemd.stopping();
    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status("always, starting, starting and stopping"));
    notify.verify(() -> SystemdNotify.status("always, running"));
    notify.verify(() -> SystemdNotify.status("always, stopping, starting and stopping"));
    // the provider is not even asked for its status in the other states, as its sources may not be available then
    assertThat(runningCalls).hasValue(1);
  }

  @Test
  void asksStoppingProvidersForTheirStatusWhenStoppingBeforeReady() {
    systemd.addStatusProviders(new Running(new AtomicInteger()), new Stopping());

    systemd.stopping();
    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status("stopping"));
  }

  @Test
  void stopsSendingStatusWhenProvidersAreRemoved() {
    StatusProvider first = provider("first");
    StatusProvider second = provider("second");
    systemd.addStatusProviders(first, second);

    systemd.removeStatusProviders(first, provider("not registered"));
    systemd.updateStatus();
    systemd.removeStatusProviders(second);
    systemd.updateStatus();

    // only the remaining provider is called, and nothing is sent once there are none
    notify.verify(() -> SystemdNotify.status("second"));
    notify.verify(() -> SystemdNotify.status(anyString()), Mockito.times(1));
    assertThat(systemd.getStatusProviders()).isEmpty();
  }

  @Test
  void returnsSnapshotOfStatusProviders() {
    StatusProvider first = provider("first");
    systemd.addStatusProviders(first);

    List<StatusProvider> snapshot = systemd.getStatusProviders();
    systemd.addStatusProviders(provider("second"));

    assertThat(snapshot).containsExactly(first);
    assertThatThrownBy(() -> snapshot.add(provider("third"))).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void addsStatusProvidersInOrder() {
    StatusProvider first = provider("first");
    StatusProvider second = provider("second");
    StatusProvider third = provider("third");

    systemd.addStatusProviders(second);
    systemd.addStatusProviders(0, first);
    systemd.addStatusProviders(third);

    assertThat(systemd.getStatusProviders()).containsExactly(first, second, third);
  }

  @Test
  void rejectsNullStatusProvidersWhenRegistered() {
    StatusProvider valid = provider("valid");
    List<StatusProvider> withNull = Arrays.asList(valid, null);

    assertThatThrownBy(() -> systemd.addStatusProviders(valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addStatusProviders(0, valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addStatusProviders(0, withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addStatusProviders(withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addStatusProviders((List<StatusProvider>) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Providers must not be null");
    assertThatThrownBy(() -> systemd.removeStatusProviders(valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.removeStatusProviders((StatusProvider[]) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Providers must not be null");
    assertThatThrownBy(() -> systemd.addStatusProviders(0, (StatusProvider[]) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Providers must not be null");
    assertThatThrownBy(() -> systemd.removeStatusProviders(withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.removeStatusProviders((List<StatusProvider>) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Providers must not be null");

    // a rejected registration leaves the providers unchanged, so they can still be used
    assertThat(systemd.getStatusProviders()).isEmpty();
  }

  @Test
  void removesStatusProvidersGivenAsList() {
    StatusProvider first = provider("first");
    StatusProvider second = provider("second");
    systemd.addStatusProviders(first, second);

    systemd.removeStatusProviders(List.of(first));

    assertThat(systemd.getStatusProviders()).containsExactly(second);
  }

  @Test
  void allowsProvidersToChangeProvidersWhileUpdatingStatus() {
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        systemd.addStatusProviders(provider("added"));
        return "adding";
      }
    });

    // the providers are called on a snapshot, so a provider can change them while being called, without blocking nor failing
    assertTimeoutPreemptively(Duration.ofSeconds(5), systemd::updateStatus);

    assertThat(systemd.getStatusProviders()).hasSize(2);
  }

  @Test
  void keepsUpdatingStatusWhenProviderFails() throws Exception {
    CountDownLatch invocations = new CountDownLatch(3);
    systemd.enableStatusUpdate(PERIOD);

    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        invocations.countDown();
        throw new IllegalStateException("failure");
      }
    });

    assertThat(invocations.await(5, SECONDS)).isTrue();
  }

  @Test
  void keepsTriggeringWatchdogWhenHealthProviderFails() throws Exception {
    // heartbeats are only sent once ready
    systemd.ready();
    CountDownLatch invocations = new CountDownLatch(3);
    systemd.enableWatchdog(PERIOD);

    systemd.addHealthProviders(() -> {
      invocations.countDown();
      throw new IllegalStateException("failure");
    });

    assertThat(invocations.await(5, SECONDS)).isTrue();
  }

  @Test
  void keepsUpdatingStatusWhenProviderThrowsUndeclaredCheckedException() throws Exception {
    CountDownLatch invocations = new CountDownLatch(3);
    systemd.enableStatusUpdate(PERIOD);

    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        invocations.countDown();
        return sneakyThrow(new IOException("failure"));
      }
    });

    assertThat(invocations.await(5, SECONDS)).isTrue();
  }

  @Test
  void leavesFailingStatusProvidersOutOfTheStatus() {
    StatusProvider throwing = new StatusProvider() {
      @Override
      public @NonNull String status() {
        throw new IllegalStateException("failure");
      }
    };
    StatusProvider returningNull = new StatusProvider() {
      @Override
      @SuppressWarnings("DataFlowIssue")
      public @NonNull String status() {
        return null;
      }
    };
    systemd.addStatusProviders(provider("first"), throwing, returningNull, provider("second"));

    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status("first, second"));
    assertThat(systemd.failing(throwing)).isTrue();
    assertThat(systemd.failing(returningNull)).isTrue();
  }

  @Test
  void readyDoesNotFailWhenAStatusProviderThrows() {
    StatusProvider throwing = new StatusProvider() {
      @Override
      public @NonNull String status() {
        throw new IllegalStateException("failure");
      }
    };
    systemd.addStatusProviders(throwing, provider("status"));

    assertThatCode(systemd::ready).doesNotThrowAnyException();

    notify.verify(SystemdNotify::ready);
    notify.verify(() -> SystemdNotify.status("status"));
  }

  @Test
  void clearsTheFailureOfAStatusProviderOnceItRecovers() {
    AtomicBoolean fail = new AtomicBoolean(true);
    StatusProvider flaky = new StatusProvider() {
      @Override
      public @NonNull String status() {
        if (fail.get()) {
          throw new IllegalStateException("failure");
        }
        return "recovered";
      }
    };
    systemd.addStatusProviders(flaky);
    systemd.updateStatus();
    fail.set(false);

    systemd.updateStatus();

    assertThat(systemd.failing(flaky)).isFalse();
    notify.verify(() -> SystemdNotify.status("recovered"));
  }

  @Test
  void considersAFailingHealthProviderUnhealthyAndStillAsksTheOthers() {
    systemd.ready();
    AtomicBoolean asked = new AtomicBoolean();
    HealthProvider throwing = () -> {
      throw new IllegalStateException("failure");
    };
    systemd.addHealthProviders(throwing, () -> {
      asked.set(true);
      return HealthProvider.Health.HEALTHY;
    });

    assertThatCode(systemd::watchdog).doesNotThrowAnyException();

    notify.verify(SystemdNotify::watchdog, never());
    assertThat(asked).isTrue();
    assertThat(systemd.failing(throwing)).isTrue();
  }

  @Test
  void forgetsTheFailureOfARemovedProvider() {
    HealthProvider throwing = () -> {
      throw new IllegalStateException("failure");
    };
    systemd.ready();
    systemd.addHealthProviders(throwing);
    systemd.watchdog();

    systemd.removeHealthProviders(throwing);

    assertThat(systemd.failing(throwing)).isFalse();
  }

  // throws a checked exception without declaring it, as Kotlin code or Lombok's @SneakyThrows can do
  @SuppressWarnings("unchecked")
  private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }

  @Test
  void keepsTriggeringWatchdogWhileStatusUpdateIsSlow() throws Exception {
    // heartbeats are only sent once ready
    systemd.ready();
    CountDownLatch statusStarted = new CountDownLatch(1);
    CountDownLatch releaseStatus = new CountDownLatch(1);
    CountDownLatch heartbeats = new CountDownLatch(3);
    systemd.enableStatusUpdate(PERIOD);
    systemd.enableWatchdog(PERIOD);

    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        // simulates a slow status provider, such as a health check waiting on a timeout
        statusStarted.countDown();
        try {
          releaseStatus.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "slow";
      }
    });
    systemd.addHealthProviders(() -> {
      heartbeats.countDown();
      return HealthProvider.Health.HEALTHY;
    });

    try {
      assertThat(statusStarted.await(5, SECONDS)).isTrue();
      // the watchdog keeps running while the status update is blocked
      assertThat(heartbeats.await(5, SECONDS)).isTrue();
    } finally {
      releaseStatus.countDown();
    }
  }

  @Test
  void stopsAllPeriodicTasksWhenClosed() throws Exception {
    // heartbeats are only sent once ready
    systemd.ready();
    AtomicInteger statusUpdates = new AtomicInteger();
    AtomicInteger heartbeats = new AtomicInteger();
    CountDownLatch bothRunning = new CountDownLatch(2);
    systemd.enableStatusUpdate(PERIOD);
    systemd.enableWatchdog(PERIOD);
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        if (statusUpdates.incrementAndGet() == 1) {
          bothRunning.countDown();
        }
        return "status";
      }
    });
    systemd.addHealthProviders(() -> {
      if (heartbeats.incrementAndGet() == 1) {
        bothRunning.countDown();
      }
      return HealthProvider.Health.HEALTHY;
    });
    assertThat(bothRunning.await(5, SECONDS)).isTrue();

    systemd.close();
    int statusUpdatesWhenClosed = statusUpdates.get();
    int heartbeatsWhenClosed = heartbeats.get();
    // close waits for the executors to terminate, so no task can run afterwards
    Thread.sleep(100);

    assertThat(systemd.isClosed()).isTrue();
    assertThat(statusUpdates).hasValue(statusUpdatesWhenClosed);
    assertThat(heartbeats).hasValue(heartbeatsWhenClosed);
  }

  @Test
  void waitsForTheRunningTasksWhenClosedConcurrently() throws Exception {
    CountDownLatch statusRunning = new CountDownLatch(1);
    CountDownLatch releaseStatus = new CountDownLatch(1);
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        statusRunning.countDown();
        try {
          releaseStatus.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "status";
      }
    });
    systemd.enableStatusUpdate(PERIOD);
    assertThat(statusRunning.await(5, SECONDS)).isTrue();
    Thread first = new Thread(this::close);
    first.start();
    long deadline = System.nanoTime() + SECONDS.toNanos(5);
    while (!systemd.isClosed() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(systemd.isClosed()).isTrue();

    Thread second = new Thread(this::close);
    second.start();
    second.join(200);

    // the second call waits for the status update that is still running, as the first does
    assertThat(second.isAlive()).isTrue();
    releaseStatus.countDown();
    second.join(5_000);
    first.join(5_000);
    assertThat(second.isAlive()).isFalse();
    assertThat(first.isAlive()).isFalse();
  }

  private void close() {
    try {
      systemd.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void ignoresEnablingExtendTimeoutWhenClosed() throws Exception {
    systemd.close();

    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));

    assertThat(systemd.extendTimeoutFuture()).isNull();
  }

  @Test
  void reschedulesExtendTimeoutWhenEnabledAgain() {
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();

    systemd.enableExtendTimeout(Duration.ofSeconds(7), Duration.ofSeconds(31));
    systemd.extendTimeout();

    assertThat(scheduled).isCancelled();
    assertThat(systemd.extendTimeoutFuture()).isNotNull().isNotSameAs(scheduled);
    assertThat(systemd.extendTimeoutPeriod()).isEqualTo(Duration.ofSeconds(7));
    notify.verify(() -> SystemdNotify.extendTimeout(31_000_000L));
  }

  @Test
  void reschedulesStatusUpdateWhenEnabledAgain() {
    systemd.enableStatusUpdate(Duration.ofSeconds(5));
    Future<?> scheduled = systemd.statusUpdateFuture();

    systemd.enableStatusUpdate(Duration.ofSeconds(7));

    assertThat(scheduled).isCancelled();
    assertThat(systemd.statusUpdateFuture()).isNotNull().isNotSameAs(scheduled);
    assertThat(systemd.statusUpdatePeriod()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void reschedulesWatchdogWhenEnabledAgain() {
    systemd.enableWatchdog(Duration.ofSeconds(5));
    Future<?> scheduled = systemd.watchdogFuture();

    systemd.enableWatchdog(Duration.ofSeconds(7));

    assertThat(scheduled).isCancelled();
    assertThat(systemd.watchdogFuture()).isNotNull().isNotSameAs(scheduled);
    assertThat(systemd.watchdogPeriod()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void disablesStatusUpdate() {
    systemd.enableStatusUpdate(Duration.ofSeconds(5));
    Future<?> scheduled = systemd.statusUpdateFuture();

    systemd.disableStatusUpdate();
    systemd.disableStatusUpdate();

    assertThat(scheduled).isCancelled();
    assertThat(systemd.statusUpdateFuture()).isNull();
    assertThat(systemd.statusUpdatePeriod()).isNull();
  }

  @Test
  void disablesWatchdog() {
    systemd.enableWatchdog(Duration.ofSeconds(5));
    Future<?> scheduled = systemd.watchdogFuture();

    systemd.disableWatchdog();
    systemd.disableWatchdog();

    assertThat(scheduled).isCancelled();
    assertThat(systemd.watchdogFuture()).isNull();
    assertThat(systemd.watchdogPeriod()).isNull();
  }

  @Test
  void ignoresEnablingPeriodicTasksWhenClosed() throws Exception {
    systemd.close();

    systemd.enableStatusUpdate(Duration.ofSeconds(5));
    systemd.enableWatchdog(Duration.ofSeconds(5));

    assertThat(systemd.statusUpdateFuture()).isNull();
    assertThat(systemd.watchdogFuture()).isNull();
  }

  @Test
  void keepsASingleScheduleWhenEnabledAndDisabledByManyThreadsAtOnce() throws Exception {
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> changes = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Duration period = Duration.ofSeconds(i + 1L);
        boolean disable = i % 2 == 0;
        changes.add(executor.submit(() -> {
          start.await();
          for (int j = 0; j < 100; j++) {
            systemd.enableStatusUpdate(period);
            if (disable) {
              systemd.disableStatusUpdate();
            }
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> change : changes) {
        change.get(10, SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    // every schedule replaced was cancelled, so that the one left, if any, runs at the period reported
    Future<?> scheduled = systemd.statusUpdateFuture();
    if (scheduled == null) {
      assertThat(systemd.statusUpdatePeriod()).isNull();
    } else {
      assertThat(scheduled).isNotCancelled();
      assertThat(systemd.statusUpdatePeriod()).isNotNull();
    }
  }

  @Test
  void neverFailsToEnableWhileClosing() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> enabling = executor.submit(() -> {
        start.await();
        for (int i = 0; i < 1_000; i++) {
          systemd.enableStatusUpdate(Duration.ofSeconds(5));
          systemd.enableWatchdog(Duration.ofSeconds(5));
        }
        return null;
      });
      start.countDown();
      systemd.close();

      // enabling either scheduled before the executors were shut down, which cancelled it, or was ignored, but never rejected
      enabling.get(10, SECONDS);
    } finally {
      executor.shutdownNow();
    }
    Future<?> statusUpdate = systemd.statusUpdateFuture();
    Future<?> watchdog = systemd.watchdogFuture();
    assertThat(statusUpdate == null || statusUpdate.isCancelled()).isTrue();
    assertThat(watchdog == null || watchdog.isCancelled()).isTrue();
  }

  @Test
  void disablesExtendTimeoutWhenReady() {
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();

    systemd.ready();

    assertThat(scheduled).isCancelled();
    assertThat(systemd.extendTimeoutFuture()).isNull();
  }

  @Test
  void disablesExtendTimeoutWhenStopping() {
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();

    systemd.stopping();

    assertThat(scheduled).isCancelled();
    assertThat(systemd.extendTimeoutFuture()).isNull();
  }

  @Test
  void enablesExtendTimeoutWhenStopping() {
    systemd.ready();
    systemd.stopping();

    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));

    assertThat(systemd.extendTimeoutPeriod()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void keepsTheExtensionOfTheShutdownEnabledWhileStopping() throws Exception {
    systemd.enableExtendTimeout(Duration.ofSeconds(1), Duration.ofSeconds(29));
    AtomicReference<Thread> enabling = new AtomicReference<>();
    // enabled by another thread while systemd is told that the application is stopping, once the state changed, but before the extension of the startup
    // is stopped
    notify.when(SystemdNotify::stopping).thenAnswer(invocation -> {
      Thread thread = new Thread(() -> systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29)));
      enabling.set(thread);
      thread.start();
      // it waits for stopping() to complete, rather than enabling the extension right away, only for stopping() to stop it afterwards
      thread.join(500);
      return null;
    });

    systemd.stopping();
    enabling.get().join(5000);

    assertThat(systemd.extendTimeoutPeriod()).isEqualTo(Duration.ofSeconds(5));
    assertThat(systemd.extendTimeoutFuture()).isNotNull().isNotCancelled();
  }

  @Test
  void keepsTheExtensionOfTheShutdownWhenToldAgainThatItIsStopping() {
    systemd.stopping();
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();

    systemd.stopping();

    assertThat(scheduled).isNotCancelled();
    assertThat(systemd.extendTimeoutFuture()).isSameAs(scheduled);
  }

  @Test
  void armsTheExtensionOfTheShutdownWhenReady() {
    systemd.ready();

    // in preparation for the shutdown, while the application runs
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();
    // each run of the extension, run here, as the static mock only applies to this thread: nothing is sent while the application runs, as systemd would
    // extend RuntimeMaxSec instead
    systemd.extendTimeout();

    assertThat(scheduled).isNotNull();
    notify.verify(() -> SystemdNotify.extendTimeout(Mockito.anyLong()), never());

    systemd.stopping();
    systemd.extendTimeout();

    // left enabled, and extending the shutdown from then on
    assertThat(systemd.extendTimeoutFuture()).isSameAs(scheduled);
    assertThat(scheduled).isNotCancelled();
    notify.verify(() -> SystemdNotify.extendTimeout(29_000_000L));
  }

  @Test
  void keepsTheExtensionOfTheShutdownEnabledWhileReady() throws Exception {
    systemd.enableExtendTimeout(Duration.ofSeconds(1), Duration.ofSeconds(29));
    AtomicReference<Thread> enabling = new AtomicReference<>();
    // enabled by another thread while systemd is told that the application is ready, once the state changed, but before the extension of the startup is
    // stopped
    notify.when(SystemdNotify::ready).thenAnswer(invocation -> {
      Thread thread = new Thread(() -> systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29)));
      enabling.set(thread);
      thread.start();
      // it waits for ready() to complete, rather than enabling the extension right away, only for ready() to stop it afterwards
      thread.join(500);
      return null;
    });

    systemd.ready();
    enabling.get().join(5000);

    assertThat(systemd.extendTimeoutPeriod()).isEqualTo(Duration.ofSeconds(5));
    assertThat(systemd.extendTimeoutFuture()).isNotNull().isNotCancelled();
  }

  @Test
  void keepsNotifyingSystemdDirectlyWhenClosed() throws Exception {
    systemd.addStatusProviders(provider("status"));
    systemd.close();

    systemd.updateStatus();
    systemd.stopping();

    notify.verify(() -> SystemdNotify.status("status"));
    notify.verify(SystemdNotify::stopping);
  }

  @Test
  void notifiesStoppingOnlyOnce() {
    systemd.stopping();
    systemd.stopping();

    notify.verify(SystemdNotify::stopping);
  }

  @Test
  void notifiesStoppingWhenClosedWithoutStopping() throws Exception {
    systemd.addHealthProviders(() -> HealthProvider.Health.HEALTHY);

    systemd.close();
    systemd.watchdog();

    notify.verify(SystemdNotify::stopping);
    notify.verify(SystemdNotify::watchdog, never());
  }

  @Test
  void notifiesStoppingAndTheReasonWhenFailed() {
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));

    systemd.failed("Failed: connection refused");

    InOrder inOrder = Mockito.inOrder(SystemdNotify.class);
    inOrder.verify(notify, SystemdNotify::stopping);
    inOrder.verify(notify, () -> SystemdNotify.status("Failed: connection refused"));
    // the application is on its way out, so the startup is no longer extended
    assertThat(systemd.extendTimeoutFuture()).isNull();
    assertThat(systemd.isFailed()).isTrue();
  }

  @Test
  void sendsNoStatusOnceFailed() throws Exception {
    systemd.addStatusProviders(provider("always"), new Stopping());

    systemd.failed("Failed: boom");
    // as the status updates while the application shuts down would, such as the periodic ones, or those sent directly once closed
    systemd.updateStatus();
    systemd.close();
    systemd.updateStatus();

    notify.verify(() -> SystemdNotify.status(anyString()));
    notify.verify(() -> SystemdNotify.status("Failed: boom"));
    assertThat(systemd.lastStatusUpdateSent()).isZero();
  }

  @Test
  void doesNotReplaceTheReasonOfTheFailureWithAStatusBuiltBefore() {
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        // a slow status provider, while the application fails on another thread
        completeOnAnotherThread(() -> systemd.failed("Failed: boom"));
        return "older";
      }
    });

    systemd.updateStatus();

    // the reason was sent on the other thread, where the static mock of SystemdNotify does not apply
    assertThat(systemd.isFailed()).isTrue();
    notify.verify(() -> SystemdNotify.status("older"), never());
  }

  @Test
  void sendsOnlyTheFirstReasonOfTheFailure() {
    systemd.failed("Failed: first");
    systemd.failed("Failed: second");

    notify.verify(SystemdNotify::stopping);
    notify.verify(() -> SystemdNotify.status("Failed: first"));
    notify.verify(() -> SystemdNotify.status("Failed: second"), never());
  }

  @Test
  void sendsTheReasonOfTheFailureWhenClosed() throws Exception {
    systemd.close();

    systemd.failed("Failed: boom");

    notify.verify(() -> SystemdNotify.status("Failed: boom"));
  }

  @Test
  void doesNotNotifyStoppingAgainWhenClosed() throws Exception {
    systemd.stopping();

    systemd.close();

    notify.verify(SystemdNotify::stopping);
  }

  @Test
  void notifiesStoppingBeforeStoppingWatchdogHeartbeats() throws Exception {
    systemd.enableWatchdog(PERIOD);
    Future<?> heartbeats = systemd.watchdogFuture();
    AtomicReference<Boolean> heartbeatsCancelledWhenStopping = new AtomicReference<>();
    notify.when(SystemdNotify::stopping).thenAnswer(invocation -> {
      heartbeatsCancelledWhenStopping.set(heartbeats.isCancelled());
      return null;
    });

    systemd.close();

    // shutting down the executor cancels the periodic heartbeats, which must only happen once systemd is told that the application is stopping
    assertThat(heartbeatsCancelledWhenStopping).hasValue(false);
    assertThat(heartbeats.isCancelled()).isTrue();
  }

  @Test
  void disablesWatchdogHeartbeatsOnceStoppingIsNotified() {
    systemd.enableWatchdog(PERIOD);
    Future<?> heartbeats = systemd.watchdogFuture();
    AtomicReference<Boolean> heartbeatsCancelledWhenStopping = new AtomicReference<>();
    notify.when(SystemdNotify::stopping).thenAnswer(invocation -> {
      heartbeatsCancelledWhenStopping.set(heartbeats.isCancelled());
      return null;
    });

    systemd.stopping();

    // systemd keeps enforcing the watchdog until told that the application is stopping
    assertThat(heartbeatsCancelledWhenStopping).hasValue(false);
    assertThat(heartbeats.isCancelled()).isTrue();
    assertThat(systemd.watchdogFuture()).isNull();
    assertThat(systemd.watchdogPeriod()).isNull();
  }

  @Test
  void ignoresEnablingWatchdogWhenStopping() {
    systemd.stopping();

    systemd.enableWatchdog(PERIOD);

    assertThat(systemd.watchdogFuture()).isNull();
  }

  @Test
  void returnsTheSameInstance() {
    assertThat(Systemd.instance()).isSameAs(systemd);
  }

  @Test
  void returnsTheSameInstanceOnceClosed() throws Exception {
    // as it told systemd that the application is stopping, which is the end of the service
    systemd.close();

    assertThat(Systemd.instance()).isSameAs(systemd);
  }

  @Test
  void createsASingleInstanceWhenFirstUsedByManyThreadsAtOnce() throws Exception {
    Systemd.resetProcess();
    int threads = 16;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Systemd>> instances = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        instances.add(executor.submit(() -> {
          start.await();
          return Systemd.instance();
        }));
      }
      start.countDown();

      Systemd first = instances.get(0).get(5, SECONDS);
      for (Future<Systemd> instance : instances) {
        assertThat(instance.get(5, SECONDS)).isSameAs(first);
      }
    } finally {
      executor.shutdownNow();
    }
    systemd = Systemd.instance();
  }

  @Test
  void doesNotSendAHeartbeatOnceStoppingWhileTheHealthWasChecked() {
    systemd.ready();
    // the application starts stopping while the health is checked, which may take long, after the heartbeat checked that it was not stopping
    systemd.addHealthProviders(() -> {
      systemd.stopping();
      return HealthProvider.Health.HEALTHY;
    });

    systemd.watchdog();

    notify.verify(SystemdNotify::stopping);
    notify.verify(SystemdNotify::watchdog, never());
  }

  @Test
  void tellsSystemdThatItIsReadyBeforeAnotherThreadCanTellItIsStopping() throws Exception {
    AtomicReference<Boolean> stoppingWhileTellingReady = new AtomicReference<>();
    Thread stopping = new Thread(systemd::stopping);
    notify.when(SystemdNotify::ready).thenAnswer(invocation -> {
      stopping.start();
      stopping.join(200);
      stoppingWhileTellingReady.set(!stopping.isAlive());
      return null;
    });

    systemd.ready();
    stopping.join(5_000);

    // the other thread waits for ready to be told, so that systemd receives READY=1 before STOPPING=1
    assertThat(stoppingWhileTellingReady).hasValue(false);
    assertThat(stopping.isAlive()).isFalse();
    assertThat(systemd.isReady()).isTrue();
  }

  @Test
  void doesNotSendHeartbeatsUntilReady() {
    AtomicInteger healthChecks = new AtomicInteger();
    systemd.addHealthProviders(() -> {
      healthChecks.incrementAndGet();
      return HealthProvider.Health.HEALTHY;
    });

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog, never());
    // no health check is run for a heartbeat that is not sent
    assertThat(healthChecks).hasValue(0);

    systemd.ready();
    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog);
    assertThat(healthChecks).hasValue(1);
  }

  @Test
  void stopsWatchdogHeartbeatsWhenStopping() {
    systemd.ready();
    AtomicInteger healthChecks = new AtomicInteger();
    systemd.addHealthProviders(() -> {
      healthChecks.incrementAndGet();
      return HealthProvider.Health.HEALTHY;
    });
    systemd.watchdog();

    systemd.stopping();
    systemd.watchdog();

    notify.verify(SystemdNotify::stopping);
    notify.verify(SystemdNotify::watchdog, Mockito.times(1));
    // no health check is run for a heartbeat that is not sent
    assertThat(healthChecks).hasValue(1);
  }

  @Test
  void evaluatesHealthOnceWhenUnhealthy() {
    systemd.ready();
    AtomicInteger invocations = new AtomicInteger();
    systemd.addHealthProviders(() -> {
      invocations.incrementAndGet();
      return new HealthProvider.Health(false, Map.of());
    });

    systemd.watchdog();

    assertThat(invocations).hasValue(1);
    notify.verify(SystemdNotify::watchdog, never());
  }

  @Test
  void logsHealthDetailsWhenSuppressingWatchdog() {
    systemd.ready();
    systemd.addHealthProviders(() -> new HealthProvider.Health(false, Map.of("db", Map.of("error", "Connection to db.internal:5432 refused"))));

    List<ILoggingEvent> events = logged(systemd::watchdog);

    // the details are left out of the systemd status, as any local user can read it, but operators still need them: the log is the only place they are
    // shown, so it is what is checked, rather than some state kept only for tests
    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("db={error=Connection to db.internal:5432 refused}");
    });
  }

  @Test
  void sendsHeartbeatWhenAllHealthProvidersAreHealthy() {
    systemd.ready();
    systemd.addHealthProviders(() -> HealthProvider.Health.HEALTHY, () -> HealthProvider.Health.HEALTHY);

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog);
  }

  @Test
  void suppressesHeartbeatWhenAnyHealthProviderIsUnhealthy() {
    systemd.ready();
    systemd.addHealthProviders(() -> HealthProvider.Health.HEALTHY, () -> new HealthProvider.Health(false, Map.of("db", "down")));

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog, never());
  }

  @Test
  void suppressesHeartbeatWhenAHealthProviderIsUnhealthyWithoutDetails() {
    systemd.ready();
    systemd.addHealthProviders(() -> new HealthProvider.Health(false, Map.of()));

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog, never());
  }

  @Test
  void triggersWatchdogWhenAHealthProviderIsUnrecoverable() {
    systemd.ready();
    systemd.addHealthProviders(() -> HealthProvider.Health.HEALTHY,
        () -> HealthProvider.Health.unrecoverable(Map.of("deadlockedThreads", List.of("worker-1", "worker-2"))));

    systemd.watchdog();

    // the heartbeat is suppressed as well, which is what the versions of systemd that ignore the trigger go by
    notify.verify(SystemdNotify::watchdog, never());
    notify.verify(SystemdNotify::watchdogTrigger);
  }

  @Test
  void doesNotTriggerWatchdogWhenUnhealthyButRecoverable() {
    systemd.ready();
    systemd.addHealthProviders(() -> new HealthProvider.Health(false, Map.of("db", "down")));

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdog, never());
    notify.verify(SystemdNotify::watchdogTrigger, never());
  }

  @Test
  void triggersWatchdogOnlyOnce() {
    systemd.ready();
    systemd.addHealthProviders(() -> HealthProvider.Health.unrecoverable(Map.of()));

    systemd.watchdog();
    systemd.watchdog();
    systemd.triggerWatchdog();

    notify.verify(SystemdNotify::watchdogTrigger, Mockito.times(1));
  }

  @Test
  void triggersWatchdogWhenToldByTheApplication() {
    // systemd handles the trigger at any time, even when the service unit does not set WatchdogSec, so it is sent while starting up as well
    systemd.triggerWatchdog();

    notify.verify(SystemdNotify::watchdogTrigger);
  }

  @Test
  void doesNotTriggerWatchdogOnceStopping() {
    systemd.ready();
    systemd.stopping();

    systemd.triggerWatchdog();

    notify.verify(SystemdNotify::watchdogTrigger, never());
  }

  @Test
  void doesNotAskHealthProvidersNorTriggerWatchdogWhileStartingUp() {
    systemd.addHealthProviders(() -> HealthProvider.Health.unrecoverable(Map.of()));

    systemd.watchdog();

    notify.verify(SystemdNotify::watchdogTrigger, never());
  }

  @Test
  void keepsTheDetailsOfEachUnhealthyHealthProviderApart() {
    // the same key from two providers, as the broken liveness state is reported both by the availability and by the Actuator health
    systemd.addHealthProviders(() -> new HealthProvider.Health(false, Map.of("livenessState", "BROKEN")), () -> HealthProvider.Health.HEALTHY,
        () -> new HealthProvider.Health(false, Map.of("livenessState", "DOWN", "db", "down")));

    assertThat(systemd.unhealthy()).extracting(HealthProvider.Health::details)
        .containsExactly(Map.of("livenessState", "BROKEN"), Map.of("livenessState", "DOWN", "db", "down"));
  }

  @Test
  void hasNoUnhealthyDetailsWhenHealthy() {
    systemd.addHealthProviders(() -> HealthProvider.Health.HEALTHY, () -> HealthProvider.Health.HEALTHY);

    assertThat(systemd.unhealthy()).isEmpty();
  }

  @Test
  void stopsConsultingRemovedHealthProviders() {
    systemd.ready();
    HealthProvider unhealthy = () -> new HealthProvider.Health(false, Map.of());
    HealthProvider healthy = () -> HealthProvider.Health.HEALTHY;
    systemd.addHealthProviders(unhealthy, healthy);

    systemd.removeHealthProviders(unhealthy);
    systemd.watchdog();

    assertThat(systemd.getHealthProviders()).containsExactly(healthy);
    notify.verify(SystemdNotify::watchdog);
  }

  @Test
  void returnsASnapshotOfTheHealthProviders() {
    HealthProvider first = () -> HealthProvider.Health.HEALTHY;
    systemd.addHealthProviders(first);

    List<HealthProvider> providers = systemd.getHealthProviders();
    systemd.addHealthProviders(List.of(() -> HealthProvider.Health.HEALTHY));

    assertThat(providers).containsExactly(first);
    assertThatThrownBy(() -> providers.add(first)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNullHealthProviders() {
    HealthProvider valid = () -> HealthProvider.Health.HEALTHY;
    List<HealthProvider> withNull = Arrays.asList(valid, null);

    assertThatThrownBy(() -> systemd.addHealthProviders(valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addHealthProviders(0, valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addHealthProviders(0, withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addHealthProviders(withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.addHealthProviders((List<HealthProvider>) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Health providers must not be null");
    assertThatThrownBy(() -> systemd.addHealthProviders(0, (HealthProvider[]) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Health providers must not be null");
    assertThatThrownBy(() -> systemd.removeHealthProviders(valid, null)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.removeHealthProviders((HealthProvider[]) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Health providers must not be null");
    assertThatThrownBy(() -> systemd.removeHealthProviders(withNull)).isInstanceOf(NullPointerException.class).hasMessageContaining("null elements");
    assertThatThrownBy(() -> systemd.removeHealthProviders((List<HealthProvider>) null)).isInstanceOf(NullPointerException.class)
        .hasMessage("Health providers must not be null");

    // a rejected registration leaves the providers unchanged, so they can still be used
    assertThat(systemd.getHealthProviders()).isEmpty();
  }

  @Test
  void addsHealthProvidersInOrder() {
    HealthProvider first = () -> HealthProvider.Health.HEALTHY;
    HealthProvider second = () -> HealthProvider.Health.HEALTHY;
    HealthProvider third = () -> HealthProvider.Health.HEALTHY;
    HealthProvider fourth = () -> HealthProvider.Health.HEALTHY;

    systemd.addHealthProviders(third);
    systemd.addHealthProviders(0, first);
    systemd.addHealthProviders(1, List.of(second));
    systemd.addHealthProviders(List.of(fourth));

    assertThat(systemd.getHealthProviders()).containsExactly(first, second, third, fourth);
  }

  @Test
  void removesHealthProvidersGivenAsList() {
    HealthProvider first = () -> HealthProvider.Health.HEALTHY;
    HealthProvider second = () -> HealthProvider.Health.HEALTHY;
    systemd.addHealthProviders(first, second);

    systemd.removeHealthProviders(List.of(first, () -> HealthProvider.Health.HEALTHY));

    assertThat(systemd.getHealthProviders()).containsExactly(second);
  }

  @Test
  void disablesAndReEnablesExtendTimeout() {
    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    Future<?> scheduled = systemd.extendTimeoutFuture();

    systemd.disableExtendTimeout();
    assertThat(scheduled).isCancelled();
    assertThat(systemd.extendTimeoutFuture()).isNull();

    systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
    assertThat(systemd.extendTimeoutFuture()).isNotNull().isNotSameAs(scheduled);
  }

  @Test
  void rejectsNullValues() {
    assertThatThrownBy(() -> systemd.enableStatusUpdate(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> systemd.enableWatchdog(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> systemd.enableExtendTimeout(null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> systemd.enableExtendTimeout(Duration.ofSeconds(1), null)).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsPeriodsThatAreNotPositive(long seconds) {
    Duration period = Duration.ofSeconds(seconds);

    assertThatThrownBy(() -> systemd.enableStatusUpdate(period)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> systemd.enableExtendTimeout(period, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> systemd.enableWatchdog(period)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 999})
  void rejectsExtensionAmountsShorterThanAMicrosecond(long nanos) {
    // systemd would otherwise be sent a zero or negative extension
    assertThatThrownBy(() -> systemd.enableExtendTimeout(Duration.ofSeconds(1), Duration.ofNanos(nanos))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Amount");
    assertThat(systemd.extendTimeoutFuture()).isNull();
  }

  @Test
  void warnsWhenNativeLibraryCouldNotBeLoaded() {
    UnsatisfiedLinkError failure = new UnsatisfiedLinkError("libsystemd not found");
    notify.when(SystemdNotify::loadFailure).thenReturn(Optional.of(failure));

    List<ILoggingEvent> events = logged(systemd::logStatus);

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("notifications to systemd could not be set up");
      assertThat(event.getThrowableProxy().getMessage()).isEqualTo("libsystemd not found");
    });
  }

  @Test
  void logsTheOptionsItIsConfiguredWith() throws Exception {
    utilities.when(SystemdUtilities::isUnderSystemd).thenReturn(true);
    systemd.enableStatusUpdate(Duration.ofSeconds(5));
    systemd.enableExtendTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30));
    systemd.enableWatchdog(Duration.ofSeconds(15));

    List<ILoggingEvent> events = logged(systemd::logStatus);

    assertThat(events).extracting(ILoggingEvent::getFormattedMessage).contains("Enabled Systemd integration with options=(statusUpdatePeriod=PT5S, "
        + "extendTimeoutPeriod=PT10S, extendTimeoutAmount=PT30S, watchdogPeriod=PT15S)");
  }

  @Test
  void logsTheOptionsRegardlessOfDefaultLocale() throws Exception {
    utilities.when(SystemdUtilities::isUnderSystemd).thenReturn(true);
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      systemd.enableStatusUpdate(Duration.ofSeconds(5));
      systemd.enableWatchdog(Duration.ofSeconds(15));

      List<ILoggingEvent> events = logged(systemd::logStatus);

      assertThat(events).extracting(ILoggingEvent::getFormattedMessage).contains("Enabled Systemd integration with options=(statusUpdatePeriod=PT5S, "
          + "extendTimeout=disabled, watchdogPeriod=PT15S)");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void logsEverySettingAsDisabledWhenNotConfigured() throws Exception {
    utilities.when(SystemdUtilities::isUnderSystemd).thenReturn(true);

    List<ILoggingEvent> events = logged(systemd::logStatus);

    assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
        .contains("Enabled Systemd integration with options=(statusUpdate=disabled, extendTimeout=disabled, watchdog=disabled)");
  }

  @Test
  void triggersWatchdogAtHalfTheIntervalExpectedBySystemd() {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(20_000_000L);

    systemd.enableWatchdog();

    assertThat(systemd.watchdogPeriod()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void extendsTimeoutByTheConfiguredAmount() throws Exception {
    systemd.enableExtendTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30));

    systemd.extendTimeout();

    notify.verify(() -> SystemdNotify.extendTimeout(30_000_000L));
  }

  @Test
  void extendsTimeoutByTheAmountOfThePeriodicExtensionOnceEnabled() {
    systemd.enableExtendTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30));
    systemd.disableExtendTimeout();

    systemd.extendTimeout();

    notify.verify(() -> SystemdNotify.extendTimeout(30_000_000L));
  }

  @Test
  void enablesExtendTimeout() throws Exception {
    systemd.enableExtendTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30));

    assertThat(systemd.extendTimeoutFuture()).isNotNull();
  }

  @Test
  void doesNotEnableExtendTimeoutWhenNotConfigured() {
    assertThat(systemd.extendTimeoutFuture()).isNull();
  }

  @Test
  void extendsTimeoutByTheDefaultAmountWhenNotConfigured() {
    systemd.extendTimeout();

    notify.verify(() -> SystemdNotify.extendTimeout(29_000_000L));
  }

  @Test
  void logsAtDebugWhenNotRunningUnderSystemd() {
    List<ILoggingEvent> events = logged(systemd::logStatus);

    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
      assertThat(event.getFormattedMessage()).startsWith("Not running under systemd");
    });
  }

  private static List<ILoggingEvent> logged(Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(Systemd.class);
    Level level = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      action.run();
    } finally {
      logger.setLevel(level);
      logger.detachAppender(appender);
    }
    return appender.list;
  }

  @Test
  void returnsTheWatchdogTimeoutOfTheServiceUnit() {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(30_000_000L);

    assertThat(Systemd.watchdogTimeout()).contains(Duration.ofSeconds(30));
  }

  @Test
  void returnsNoWatchdogTimeoutWhenNotExpectedBySystemd() {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(0L);

    assertThat(Systemd.watchdogTimeout()).isEmpty();
  }

  @Test
  void enablesWatchdogWhenExpectedBySystemd() throws Exception {
    // heartbeats are only sent once ready
    systemd.ready();
    CountDownLatch heartbeats = new CountDownLatch(3);
    // heartbeats every 10 ms, half of WATCHDOG_USEC
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(20_000L);

    systemd.enableWatchdog();

    // each heartbeat checks the health first
    systemd.addHealthProviders(() -> {
      heartbeats.countDown();
      return HealthProvider.Health.HEALTHY;
    });

    assertThat(heartbeats.await(5, SECONDS)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -20_000})
  void disablesWatchdogWhenNotExpectedBySystemd(long watchdogUsec) throws Exception {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(watchdogUsec);

    systemd.enableWatchdog();

    assertThat(systemd.watchdogFuture()).isNull();
  }

  @Test
  void schedulesPeriodsShorterThanAMillisecond() throws Exception {
    // heartbeats are only sent once ready
    systemd.ready();
    CountDownLatch heartbeats = new CountDownLatch(3);
    // half of a WATCHDOG_USEC of 1 ms, which systemd counts in microseconds
    systemd.enableWatchdog(Duration.ofNanos(500_000));

    systemd.addHealthProviders(() -> {
      heartbeats.countDown();
      return HealthProvider.Health.HEALTHY;
    });

    assertThat(heartbeats.await(5, SECONDS)).isTrue();
  }

  @Test
  void triggersWatchdogEveryMicrosecondWhenExpectedBySystemdEveryTwo() {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(2L);

    systemd.enableWatchdog();

    assertThat(systemd.watchdogPeriod()).isEqualTo(Duration.ofNanos(1_000));
  }

  @Test
  void rejectsAWatchdogExpectedEveryMicrosecond() {
    utilities.when(SystemdUtilities::watchdogUsec).thenReturn(1L);
    // half of it is shorter than the resolution of the periodic tasks
    assertThatThrownBy(systemd::enableWatchdog).isInstanceOf(IllegalStateException.class).hasMessageContaining("WATCHDOG_USEC=1");
  }

  @Test
  void rejectsPeriodsShorterThanAMicrosecond() {
    assertThatThrownBy(() -> systemd.enableWatchdog(Duration.ofNanos(999))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enablesWatchdog() throws Exception {
    systemd.enableWatchdog(Duration.ofSeconds(10));

    assertThat(systemd.watchdogFuture()).isNotNull();
  }
}
