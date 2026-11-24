/*
 * Copyright 2018-2026 Joao Silva
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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.awaitTermination;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.newExecutor;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.notifySocketPath;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.osName;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.requireAtLeastOneMicrosecond;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.requireHealthProviders;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.requireProviders;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.slf4j.LoggerFactory.getLogger;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The main systemd integration class.
 *
 * <p>Implements and exposes most of the {@code sd_notify} protocol, as well as some convenience methods.
 *
 * <p>Applications are expected to:
 * <ol>
 *   <li>get the instance with {@link #instance()}, as early as possible, optionally enabling the periodic tasks they need: status updates with
 *   {@link #enableStatusUpdate(Duration)}, watchdog heartbeats with {@link #enableWatchdog()}, timeout extensions with
 *   {@link #enableExtendTimeout(Duration, Duration)}; each can be changed or disabled at any time;</li>
 *   <li>optionally, register status providers with {@link #addStatusProviders(StatusProvider...)}, and health providers with
 *   {@link #addHealthProviders(HealthProvider...)};</li>
 *   <li>call {@link #ready()} once the application has started;</li>
 *   <li>on shutdown, call {@link #stopping()} before stopping the application, which also stops the watchdog heartbeats, or, if the application fails,
 *   such as when its startup fails, call {@link #failed(String)} instead, which also tells systemd why;</li>
 *   <li>call {@link #close()} last, once the application has stopped, to stop the periodic tasks; it also calls {@link #stopping()}, if not called
 *   before.</li>
 * </ol>
 *
 * <p>There is a single instance per process, as systemd follows a service through a single process, which goes through a single startup and a single
 * shutdown: once closed, the instance has told systemd that the application is stopping, so there is nothing left to tell. When not running under systemd,
 * as told by {@link #isUnderSystemd()}, the instance can still be used, but nothing reaches systemd.
 *
 * <p>All methods can be called from any thread.
 *
 * <p>The periodic tasks are scheduled with a resolution of a microsecond, the one systemd uses for its own intervals, such as <code>WATCHDOG_USEC</code>:
 * their periods are truncated to whole microseconds, and must be of at least a microsecond.
 *
 * @author Joao Silva
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class Systemd {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final Object instanceLock = new Object();

  // the instance of this process, created on first use, changed while holding the instance lock
  @Nullable
  private static volatile Systemd instance;

  // the lifecycle of the application, as told to systemd: starting until it is ready (the service unit is activating), then ready (active), then stopping
  // (deactivating); it only moves forward, but may skip ready, as the application can stop before it is ready, as when its startup fails, or systemd stops
  // it while it starts up
  private enum State {
    STARTING, READY, STOPPING
  }

  // the notifications that depend on the state are sent while holding this lock, together with the change of state, or with the check of the state they
  // depend on, so that systemd receives them in the order of the changes: a watchdog heartbeat or a timeout extension that checked the state before it
  // changed cannot reach systemd after the notification of the change, nor can READY=1 reach it after STOPPING=1; the notifications are single datagrams,
  // which are quick to send
  private final Lock stateLock = new ReentrantLock();

  // changed while holding the state lock, but read without it where nothing is sent
  private volatile State state = State.STARTING;

  // whether systemd was told to trigger the watchdog, which it is only told once; guarded by the state lock
  private boolean watchdogTriggered;

  // each type of periodic task runs on its own executor, so that none can delay another: in particular, the status updates call the status providers, some
  // of which may be slow (such as health checks), and must not delay the watchdog heartbeats nor the timeout extensions

  private final ScheduledExecutorService statusExecutor = newExecutor("jsystemd-status");

  private final ScheduledExecutorService watchdogExecutor = newExecutor("jsystemd-watchdog");

  private final ScheduledExecutorService extendTimeoutExecutor = newExecutor("jsystemd-extend-timeout");

  // changed by the application, read by the status update task
  private final List<StatusProvider> statusProviders = new CopyOnWriteArrayList<>();

  // changed by the application, read by the watchdog task
  private final List<HealthProvider> healthProviders = new CopyOnWriteArrayList<>();

  // the providers whose last call failed, so that only the first of consecutive failures of each is warned about, as they are called periodically
  private final Set<Object> failingProviders = ConcurrentHashMap.newKeySet();

  // the number of the last status update started, and, guarded by the status lock, of the last one sent: an update is only sent if none that started after
  // it was sent first, so that a status built from older values, which took longer to build, as the status providers may be slow, does not replace a newer
  // one, such as the stage of the shutdown; only the sending is guarded, so that no update waits for the status providers called by another
  private final AtomicLong statusUpdatesStarted = new AtomicLong();
  private final Lock statusLock = new ReentrantLock();
  private long lastStatusUpdateSent;

  // whether systemd was sent the reason of the failure of the application, which is the last status it is sent; guarded by the status lock
  private boolean failureSent;

  // guards the periodic tasks and whether this instance is closed, so that each task runs at the period it reports, and that nothing is scheduled once the
  // executors are shut down; when held together with the state lock, as by stopping(), it is taken first, and nothing that holds the state lock takes it
  private final Lock scheduleLock = new ReentrantLock();

  // guarded by scheduleLock
  private final PeriodicTask statusUpdateTask = new PeriodicTask(statusExecutor, "status update", this::updateStatus);

  // guarded by scheduleLock
  private final PeriodicTask watchdogTask = new PeriodicTask(watchdogExecutor, "watchdog", this::watchdog);

  // guarded by scheduleLock
  private final PeriodicTask extendTimeoutTask = new PeriodicTask(extendTimeoutExecutor, "timeout extension", this::extendTimeout);

  // whether the periodic extension of the timeout was enabled while starting up, as an extension of the startup, which ready() and stopping() stop, rather
  // than while running or stopping, as an extension of the shutdown, which runs until this instance is closed; guarded by scheduleLock
  private boolean extendTimeoutOfStartup;

  // if this instance is closed, which is independent of the state of the service unit; guarded by scheduleLock
  private boolean closed;

  // the amount of time to extend the timeout by, in microseconds: 29 seconds unless the periodic extension of the timeout is enabled with another amount;
  // changed while holding the schedule lock
  private volatile long extendTimeoutAmount = MICROSECONDS.convert(29, SECONDS);

  private Systemd() {
  }

  /**
   * Returns the instance of this process, creating it on first use. Creating it does not notify systemd, nor start any periodic task.
   *
   * @return the instance of this process, which is the same on every call, even once closed
   */
  public static Systemd instance() {
    Systemd local = instance;
    if (local == null) {
      synchronized (instanceLock) {
        local = instance;
        if (local == null) {
          local = new Systemd();
          instance = local;
        }
      }
    }
    return local;
  }

  // for tests: forgets the instance of this process, stopping its periodic tasks without notifying systemd, as each test acts as if it were a process of
  // its own
  static void resetProcess() {
    synchronized (instanceLock) {
      Systemd local = instance;
      if (local != null) {
        local.stopTasksNow();
        instance = null;
      }
    }
  }

  // for tests: replaces the instance of this process, such as with a mock, stopping the periodic tasks of the current one as resetProcess does
  static void replaceInstance(Systemd systemd) {
    requireNonNull(systemd, "Systemd must not be null");
    synchronized (instanceLock) {
      resetProcess();
      instance = systemd;
    }
  }

  // stops the periodic tasks without waiting for them; not private, so that it does nothing on a mock, whose executors are not created
  void stopTasksNow() {
    statusExecutor.shutdownNow();
    watchdogExecutor.shutdownNow();
    extendTimeoutExecutor.shutdownNow();
  }

  /**
   * Adds the status providers to the end of the list of providers.
   *
   * @param providers the providers to add
   */
  public void addStatusProviders(StatusProvider... providers) {
    addStatusProviders(Arrays.asList(requireNonNull(providers, "Providers must not be null")));
  }

  /**
   * Adds the status providers to the end of the list of providers.
   *
   * @param providers the providers to add
   */
  public void addStatusProviders(List<StatusProvider> providers) {
    this.statusProviders.addAll(requireProviders(providers));
  }

  /**
   * Adds the status providers to the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addStatusProviders(int index, StatusProvider... providers) {
    addStatusProviders(index, Arrays.asList(requireNonNull(providers, "Providers must not be null")));
  }

  /**
   * Adds the status providers to the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addStatusProviders(int index, List<StatusProvider> providers) {
    this.statusProviders.addAll(index, requireProviders(providers));
  }

  /**
   * Removes the status providers from the list of providers. Providers that are not registered are ignored.
   *
   * @param providers the providers to remove
   */
  public void removeStatusProviders(StatusProvider... providers) {
    removeStatusProviders(Arrays.asList(requireNonNull(providers, "Providers must not be null")));
  }

  /**
   * Removes the status providers from the list of providers. Providers that are not registered are ignored.
   *
   * @param providers the providers to remove
   */
  public void removeStatusProviders(List<StatusProvider> providers) {
    List<StatusProvider> removed = requireProviders(providers);
    this.statusProviders.removeAll(removed);
    removed.forEach(failingProviders::remove);
  }

  /**
   * Returns a read only snapshot of the current providers. Later changes to the providers are not reflected in the returned list.
   *
   * @return the current list of providers
   */
  public List<StatusProvider> getStatusProviders() {
    return List.copyOf(statusProviders);
  }

  /**
   * Adds the health providers to the end of the list of health providers. The watchdog heartbeat is only sent while all of them report the application as
   * healthy.
   *
   * @param providers the providers to add
   */
  public void addHealthProviders(HealthProvider... providers) {
    addHealthProviders(Arrays.asList(requireNonNull(providers, "Health providers must not be null")));
  }

  /**
   * Adds the health providers to the end of the list of health providers. The watchdog heartbeat is only sent while all of them report the application as
   * healthy.
   *
   * @param providers the providers to add
   */
  public void addHealthProviders(List<HealthProvider> providers) {
    healthProviders.addAll(requireHealthProviders(providers));
  }

  /**
   * Adds the health providers to the list of health providers in the specified position. The watchdog heartbeat is only sent while all of them report the
   * application as healthy.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addHealthProviders(int index, HealthProvider... providers) {
    addHealthProviders(index, Arrays.asList(requireNonNull(providers, "Health providers must not be null")));
  }

  /**
   * Adds the health providers to the list of health providers in the specified position. The watchdog heartbeat is only sent while all of them report the
   * application as healthy.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addHealthProviders(int index, List<HealthProvider> providers) {
    healthProviders.addAll(index, requireHealthProviders(providers));
  }

  /**
   * Removes the health providers from the list of health providers. Providers that are not registered are ignored.
   *
   * @param providers the providers to remove
   */
  public void removeHealthProviders(HealthProvider... providers) {
    removeHealthProviders(Arrays.asList(requireNonNull(providers, "Health providers must not be null")));
  }

  /**
   * Removes the health providers from the list of health providers. Providers that are not registered are ignored.
   *
   * @param providers the providers to remove
   */
  public void removeHealthProviders(List<HealthProvider> providers) {
    List<HealthProvider> removed = requireHealthProviders(providers);
    healthProviders.removeAll(removed);
    removed.forEach(failingProviders::remove);
  }

  /**
   * Returns a read only snapshot of the current health providers. Later changes to the providers are not reflected in the returned list.
   *
   * @return the current list of health providers
   */
  public List<HealthProvider> getHealthProviders() {
    return List.copyOf(healthProviders);
  }

  /**
   * Enables the periodic status updates, which send the status to systemd with {@link #updateStatus()} at the given period. If already enabled, they are
   * rescheduled at the new period. Does nothing once this instance is closed.
   *
   * @param period the period to use, which must be of at least a microsecond
   * @throws IllegalArgumentException if the period is shorter than a microsecond
   * @see #disableStatusUpdate()
   */
  public void enableStatusUpdate(Duration period) {
    enable(statusUpdateTask, period);
  }

  /**
   * Disables the periodic status updates. Does nothing if they are not enabled.
   *
   * @see #enableStatusUpdate(Duration)
   */
  public void disableStatusUpdate() {
    disable(statusUpdateTask);
  }

  // for tests: the period of the status updates, or null if they are not enabled
  @Nullable
  Duration statusUpdatePeriod() {
    return period(statusUpdateTask);
  }

  // for tests: the periodic status updates, or null if they are not enabled
  @Nullable
  Future<?> statusUpdateFuture() {
    return future(statusUpdateTask);
  }

  /**
   * Enables the periodic watchdog heartbeats, which are sent with {@link #watchdog()} at the given period, once the application is ready. If already
   * enabled, they are rescheduled at the new period. Does nothing once the application is stopping, as no heartbeat is sent from then on, nor once this
   * instance is closed. The periodic heartbeats stop once the application is stopping.
   *
   * @param period the period to use, which must be of at least a microsecond
   * @throws IllegalArgumentException if the period is shorter than a microsecond
   * @see #enableWatchdog()
   * @see #disableWatchdog()
   */
  public void enableWatchdog(Duration period) {
    requireAtLeastOneMicrosecond(period, "Period");

    scheduleLock.lock();
    try {
      // nothing is scheduled once stopping: if this runs before stopping() changes the state, it then cancels what was scheduled, as it waits for this
      // lock to do so
      if (closed || state == State.STOPPING) {
        logger.debug("Not enabling the periodic watchdog heartbeats, as the application is stopping (closed={})", closed);
      } else {
        watchdogTask.schedule(period);
      }
    } finally {
      scheduleLock.unlock();
    }
  }

  /**
   * Enables the periodic watchdog heartbeats when systemd expects them, that is, when the service unit sets <code>WatchdogSec</code>. The heartbeats are
   * sent at half the interval systemd sets in the <code>WATCHDOG_USEC</code> environment variable, as systemd recommends. When the variable is not set,
   * this method does nothing.
   *
   * @throws IllegalStateException if <code>WATCHDOG_USEC</code> is 1, as half of it is shorter than a microsecond, the resolution of the periodic tasks
   * @see #enableWatchdog(Duration)
   */
  public void enableWatchdog() {
    long watchdogUsec = SystemdUtilities.watchdogUsec();
    if (watchdogUsec == 1L) {
      throw new IllegalStateException("Cannot send watchdog heartbeats at half of WATCHDOG_USEC=1, which is shorter than a microsecond");
    }
    if (watchdogUsec > 0L) {
      enableWatchdog(Duration.of(watchdogUsec / 2, ChronoUnit.MICROS));
    }
  }

  /**
   * Disables the periodic watchdog heartbeats. Does nothing if they are not enabled. Once the heartbeats stop, systemd considers the application hung after
   * <code>WatchdogSec</code>, and kills it, unless the application is stopping.
   *
   * @see #enableWatchdog(Duration)
   */
  public void disableWatchdog() {
    disable(watchdogTask);
  }

  // for tests: the period of the watchdog heartbeats, or null if they are not enabled
  @Nullable
  Duration watchdogPeriod() {
    return period(watchdogTask);
  }

  // for tests: the periodic watchdog heartbeats, or null if they are not enabled
  @Nullable
  Future<?> watchdogFuture() {
    return future(watchdogTask);
  }

  /**
   * Enables the periodic extension of the timeout that systemd enforces while the application starts up or stops: <code>TimeoutStartSec</code> until
   * {@link #ready()} is called, and <code>TimeoutStopSec</code> once {@link #stopping()} is called. If already enabled, it is rescheduled at the new period.
   *
   * <p>Enabled while the application starts up, it extends the startup, and stops once the application is ready, or once it starts stopping, so that the
   * extension of the startup never carries over to the shutdown, where it would keep a hung shutdown from ever timing out. Enabled once the application is
   * ready, or stopping, it extends the shutdown, until this instance is closed: enabled while the application runs, it only starts extending the timeout
   * once {@link #stopping()} is called, as systemd would extend <code>RuntimeMaxSec</code> instead until then, so that the shutdown can be prepared for
   * in advance. Does nothing once this instance is closed.
   *
   * @param period the period to use, which must be of at least a microsecond
   * @param amount the amount of time to extend the timeout by, each time, which must be of at least a microsecond; also used by {@link #extendTimeout()}
   *     from then on
   * @throws IllegalArgumentException if the period or the amount is shorter than a microsecond
   * @see #extendTimeout()
   * @see #disableExtendTimeout()
   */
  public void enableExtendTimeout(Duration period, Duration amount) {
    requireAtLeastOneMicrosecond(period, "Period");
    requireAtLeastOneMicrosecond(amount, "Amount");

    scheduleLock.lock();
    try {
      if (closed) {
        logger.debug("Not enabling the periodic extension of the timeout, as this instance is closed");
      } else {
        extendTimeoutAmount = MICROSECONDS.convert(amount);
        extendTimeoutTask.schedule(period);
        // ready() and stopping() change the state while holding this lock, so that it cannot change between this and the schedule: an extension enabled
        // before either of them extends the startup, and they stop it, while one enabled after either extends the shutdown, and they leave it
        extendTimeoutOfStartup = state == State.STARTING;
      }
    } finally {
      scheduleLock.unlock();
    }
  }

  /**
   * Disables the periodic extension of the timeout. Does nothing if it is not enabled.
   *
   * @see #extendTimeout()
   * @see #enableExtendTimeout(Duration, Duration)
   */
  public void disableExtendTimeout() {
    disable(extendTimeoutTask);
  }

  // for tests: the period of the periodic extension of the timeout, or null if it is not enabled
  @Nullable
  Duration extendTimeoutPeriod() {
    return period(extendTimeoutTask);
  }

  // for tests: the periodic extension of the timeout, or null if it is not enabled
  @Nullable
  Future<?> extendTimeoutFuture() {
    return future(extendTimeoutTask);
  }

  private void enable(PeriodicTask task, Duration period) {
    requireAtLeastOneMicrosecond(period, "Period");
    scheduleLock.lock();
    try {
      if (closed) {
        logger.debug("Not enabling the periodic {}, as this instance is closed", task.name);
      } else {
        task.schedule(period);
      }
    } finally {
      scheduleLock.unlock();
    }
  }

  private void disable(PeriodicTask task) {
    scheduleLock.lock();
    try {
      task.cancel();
    } finally {
      scheduleLock.unlock();
    }
  }

  private @Nullable Duration period(PeriodicTask task) {
    scheduleLock.lock();
    try {
      return task.period;
    } finally {
      scheduleLock.unlock();
    }
  }

  private @Nullable Future<?> future(PeriodicTask task) {
    scheduleLock.lock();
    try {
      return task.future;
    } finally {
      scheduleLock.unlock();
    }
  }

  /**
   * Forces the current status to be calculated and sent to systemd. The method {@link #enableStatusUpdate(Duration)} can be used to enable periodic
   * status updates.
   *
   * <p>Only the status providers that report in the current state of the application are asked for their status: those that implement
   * {@link StartingStatusProvider}, {@link RunningStatusProvider} or {@link StoppingStatusProvider} only in the states they implement, the others in every
   * state.
   *
   * <p>If there are no status providers, nothing is sent to systemd. A status provider that throws, or returns <code>null</code>, is left out of the status,
   * as it would otherwise fail the caller, such as {@link #ready()}; the first of its consecutive failures is logged as a warning.
   *
   * <p>A status longer than systemd accepts, of 4089 bytes in UTF-8, is cut to fit, between two of its characters, and ends with an ellipsis: what is cut
   * is what the last status providers show.
   *
   * <p>Concurrent updates do not wait for each other. The status is not sent if an update that started later was sent first, so that systemd is never left
   * with an older status than the latest one sent.
   *
   * <p>Once {@link #failed(String)} is called, nothing is sent, so that systemd keeps showing the reason of the failure.
   */
  public void updateStatus() {
    // iterating a copy-on-write list works on a snapshot, so providers can be added while they are called, which may take long
    if (statusProviders.isEmpty()) {
      return;
    }
    long update = statusUpdatesStarted.incrementAndGet();
    // read once, so that the whole status is built for the same state, even if it changes meanwhile
    State state = this.state;
    String status = statusProviders.stream().filter(provider -> reportsIn(provider, state)).map(this::status).filter(t -> !t.isEmpty())
        .collect(Collectors.joining(", "));
    statusLock.lock();
    try {
      if (failureSent) {
        logger.debug("Status not sent, as the reason of the failure of the application was sent: {}", status);
        return;
      }
      if (update < lastStatusUpdateSent) {
        logger.debug("Status not sent, as a newer one was sent meanwhile: {}", status);
        return;
      }
      lastStatusUpdateSent = update;
      SystemdNotify.status(status);
    } finally {
      statusLock.unlock();
    }
  }

  // for tests: the number of the last status update sent, counting from 1 for the first one started
  long lastStatusUpdateSent() {
    statusLock.lock();
    try {
      return lastStatusUpdateSent;
    } finally {
      statusLock.unlock();
    }
  }

  // whether the provider is asked for its status in the given state: in every state if it implements none of the marker interfaces of the states
  private static boolean reportsIn(StatusProvider provider, State state) {
    boolean starting = provider instanceof StartingStatusProvider;
    boolean running = provider instanceof RunningStatusProvider;
    boolean stopping = provider instanceof StoppingStatusProvider;
    if (!starting && !running && !stopping) {
      return true;
    }
    return switch (state) {
      case STARTING -> starting;
      case READY -> running;
      case STOPPING -> stopping;
    };
  }

  private String status(StatusProvider provider) {
    try {
      String status = requireNonNull(provider.status(), "Status provider returned null");
      recovered(provider);
      return status;
    } catch (Exception e) {
      providerFailed(provider, "Status provider {} failed, leaving it out of the status", e);
      return "";
    }
  }

  private void providerFailed(Object provider, String message, Exception e) {
    if (failingProviders.add(provider)) {
      logger.warn(message, provider, e);
    } else {
      logger.debug(message, provider, e);
    }
  }

  private void recovered(Object provider) {
    if (failingProviders.remove(provider)) {
      logger.info("Provider {} works again", provider);
    }
  }

  // for tests: whether the last call of a status or health provider failed
  boolean failing(Object provider) {
    return failingProviders.contains(provider);
  }

  /**
   * Forces the timeout to be extended. The amount of time to extend by is the one of the periodic extension of the timeout, if enabled with
   * {@link #enableExtendTimeout(Duration, Duration)}, or 29 seconds otherwise.
   *
   * <p>The timeout is only extended while the application starts up or stops, that is, before {@link #ready()} is called, extending
   * <code>TimeoutStartSec</code>, or once {@link #stopping()} is called, extending <code>TimeoutStopSec</code>. Nothing is sent while the application
   * runs, as systemd would then extend <code>RuntimeMaxSec</code> instead.
   *
   * @see #enableExtendTimeout(Duration, Duration)
   */
  public void extendTimeout() {
    stateLock.lock();
    try {
      if (state != State.READY) {
        SystemdNotify.extendTimeout(extendTimeoutAmount);
      }
    } finally {
      stateLock.unlock();
    }
  }

  /**
   * Forces the watchdog timestamp to be updated. The methods {@link #enableWatchdog()} and {@link #enableWatchdog(Duration)} can be used to
   * enable periodic watchdog updates.
   *
   * <p>The watchdog timestamp is only updated once {@link #ready()} is called, and until {@link #stopping()} is called: systemd only enforces the watchdog
   * once the startup is complete, and enforces <code>TimeoutStartSec</code> until then. If any health provider reports the application as unhealthy, the
   * watchdog timestamp is not updated either, and if any reports a state the application cannot recover from, the watchdog is also triggered right away,
   * with {@link #triggerWatchdog()}.
   */
  public void watchdog() {
    // systemd handles a heartbeat without checking the state of the service, so a heartbeat sent during startup could make it enforce the watchdog before
    // the startup is complete, and kill the application if the heartbeats stop before it is ready, such as while a health provider reports it as unhealthy
    if (state != State.READY) {
      logger.debug("Not triggering heartbeat to watchdog, as the application is not running (state={})", state);
      return;
    }
    List<HealthProvider.Health> unhealthy = unhealthy();
    if (!unhealthy.isEmpty()) {
      logger.warn("Suppressing heartbeat to watchdog because application is unhealthy (details={})",
          unhealthy.stream().map(HealthProvider.Health::details).toList());
      // a state the application cannot recover from never resolves by itself, so systemd is told right away, rather than left to find out once
      // WatchdogSec elapses; the heartbeat is suppressed all the same, which is what the versions of systemd that ignore the trigger go by
      if (unhealthy.stream().anyMatch(HealthProvider.Health::unrecoverable)) {
        triggerWatchdog();
      }
      return;
    }
    boolean sent = false;
    stateLock.lock();
    try {
      // checked again, as the application may have started stopping while the health providers were asked, which can take long
      if (state == State.READY) {
        SystemdNotify.watchdog();
        sent = true;
      }
    } finally {
      stateLock.unlock();
    }
    if (sent) {
      logger.debug("Triggered heartbeat to watchdog");
    } else {
      logger.debug("Not triggering heartbeat to watchdog, as the application is stopping");
    }
  }

  // the health of each unhealthy health provider, in the order of the providers, or none if the application is healthy: every provider is asked, so that
  // the details of all the unhealthy ones are logged, and they are kept apart, as different providers may use the same keys, such as livenessState
  List<HealthProvider.Health> unhealthy() {
    List<HealthProvider.Health> unhealthy = new ArrayList<>();
    for (HealthProvider provider : healthProviders) {
      HealthProvider.Health health = health(provider);
      if (!health.healthy()) {
        unhealthy.add(health);
      }
    }
    return unhealthy;
  }

  /**
   * Tells systemd that the application detected an error it cannot recover from, so that systemd handles it as it does a watchdog timeout, right away,
   * rather than once <code>WatchdogSec</code> elapses without a heartbeat: by default, it aborts the application, and then restarts it when the service
   * unit says so, as with <code>Restart=on-watchdog</code> or <code>Restart=on-failure</code>.
   *
   * <p>{@link #watchdog()} calls this method when a health provider reports such a state (see {@link HealthProvider.Health#unrecoverable(Map)}), so
   * applications that report their health through health providers do not need to. It is there for the applications that detect such an error by other
   * means.
   *
   * <p>Requires systemd 243 or later, which handles it even when the service unit does not set <code>WatchdogSec</code>; older versions ignore it. Only
   * the first call notifies systemd, later calls have no effect. Does nothing once the application is stopping, as it is then on its way out.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#WATCHDOG=trigger">watchdog trigger</a>
   */
  public void triggerWatchdog() {
    stateLock.lock();
    try {
      if (state != State.STOPPING && !watchdogTriggered) {
        watchdogTriggered = true;
        SystemdNotify.watchdogTrigger();
      }
    } finally {
      stateLock.unlock();
    }
  }

  // a health provider that throws is unhealthy, as it cannot tell that the application is healthy, but the other providers are still asked
  private HealthProvider.Health health(HealthProvider provider) {
    try {
      HealthProvider.Health health = requireNonNull(provider.health(), "Health provider returned null");
      recovered(provider);
      return health;
    } catch (Exception e) {
      providerFailed(provider, "Health provider {} failed, considering the application unhealthy", e);
      return new HealthProvider.Health(false, Map.of(provider.getClass().getName(), "failed: " + e));
    }
  }

  /**
   * Notifies systemd that the application is ready. Also disables the periodic extension of the startup timeout, as the startup timeout no longer
   * applies, and systemd would extend <code>RuntimeMaxSec</code> instead. A periodic extension of the shutdown, enabled once ready, is left enabled.
   *
   * <p>Only the first call notifies systemd, later calls have no effect. Does nothing once the application is stopping.
   *
   * @see #disableExtendTimeout()
   */
  public void ready() {
    // the schedule lock is held from the change of state to the end of the periodic extension of the startup, so that an extension of the shutdown enabled
    // meanwhile, which waits for the lock, and then sees that the application is ready, is not stopped along with it
    scheduleLock.lock();
    try {
      stateLock.lock();
      try {
        if (state != State.STARTING) {
          return;
        }
        state = State.READY;
        SystemdNotify.ready();
      } finally {
        stateLock.unlock();
      }
      if (extendTimeoutOfStartup) {
        extendTimeoutTask.cancel();
      }
    } finally {
      scheduleLock.unlock();
    }
    updateStatus();
  }

  /**
   * Returns if the startup of the application is over.
   *
   * @return {@code true} if and only if {@link #ready()} or {@link #stopping()} have been called, as an application can stop before it is ready
   */
  public boolean isReady() {
    return state != State.STARTING;
  }

  /**
   * Signals that the application is stopping. From then on, no more watchdog heartbeats are sent, and the periodic watchdog heartbeats are disabled, as is
   * the periodic extension of the startup timeout, if the application stops before it is ready. The timeout can still be extended while the application
   * stops, with {@link #extendTimeout()} or by a periodic extension of the shutdown, enabled once ready or once stopping, which is left enabled.
   *
   * <p>Only the first call notifies systemd, later calls have no effect. {@link #close()} calls this method, if not called before.
   */
  public void stopping() {
    // systemd stops enforcing the watchdog once told the service is stopping, and only enforces TimeoutStopSec from then on; but it handles a heartbeat
    // without checking the state of the service, so a heartbeat sent after this could re-arm the watchdog, and systemd would then kill the application if the
    // heartbeats stop (for instance, once this instance is closed) while it is still shutting down; the state changes while sending, which no heartbeat can
    // be sent during, so that none is sent afterwards
    // the schedule lock is held from the change of state to the end of the periodic extension of the startup timeout, so that an extension of the shutdown
    // enabled meanwhile, which waits for the lock, and then sees that the application is stopping, is not stopped along with it
    scheduleLock.lock();
    try {
      boolean started;
      stateLock.lock();
      try {
        started = state != State.STOPPING;
        if (started) {
          state = State.STOPPING;
          SystemdNotify.stopping();
        }
      } finally {
        stateLock.unlock();
      }
      // the periodic extension of the startup timeout is stopped as well, as it would otherwise keep extending whichever timeout systemd enforces, so that a
      // hung shutdown would never time out: TimeoutStopSec once systemd stops the service, or TimeoutStartSec while the service is still activating, as
      // systemd only records STOPPING=1 sent while activating, and keeps the service activating until the application exits; only when the shutdown
      // starts, so that a later call, such as the one of close(), does not stop an extension of the shutdown enabled since
      if (started && extendTimeoutOfStartup) {
        extendTimeoutTask.cancel();
      }
    } finally {
      scheduleLock.unlock();
    }
    // the periodic heartbeats are stopped only once systemd is told, as it keeps enforcing the watchdog until then; a heartbeat that runs meanwhile sends
    // nothing, as the state is checked when sending
    disableWatchdog();
  }

  /**
   * Signals that the application failed, such as when its startup fails, and why: systemd is told that the application is stopping, with
   * {@link #stopping()}, and is then sent the reason as the status of the service unit, which <code>systemctl status</code> shows once the application has
   * exited. It is the last status sent: from then on, {@link #updateStatus()} sends nothing, so that no status provider, such as one that reports the
   * shutdown, replaces it.
   *
   * <p>The reason is sent as {@link #updateStatus()} sends a status: its line breaks are replaced by spaces, and it is cut to fit in the status systemd
   * accepts.
   *
   * <p>Only the first call sends the reason, later calls only call {@link #stopping()}, which has no effect either.
   *
   * @param reason why the application failed, such as the message of the exception that failed it
   */
  public void failed(String reason) {
    requireNonNull(reason, "Reason must not be null");
    // told it is stopping first, which stops the periodic extension of the startup timeout and the watchdog heartbeats, as the application is on its way out
    stopping();
    statusLock.lock();
    try {
      // sent while holding the status lock, as status updates are, so that none that was building the status meanwhile can be sent after it
      if (!failureSent) {
        failureSent = true;
        logger.info("Notifying systemd that service failed: {}", reason);
        SystemdNotify.status(reason);
      }
    } finally {
      statusLock.unlock();
    }
  }

  // for tests: whether the reason of the failure of the application was sent
  boolean isFailed() {
    statusLock.lock();
    try {
      return failureSent;
    } finally {
      statusLock.unlock();
    }
  }

  /**
   * Returns if the application runs under systemd, that is, if the notifications sent to systemd reach it: the operating system is Linux, systemd set the
   * {@code NOTIFY_SOCKET} environment variable, and the notifications could be set up: on Java 22 and later, with the Foreign Function and Memory API,
   * unless native access is denied, and otherwise, as on Java 17 to 21 or for {@code vsock:} addresses, the native libsystemd library could be loaded. When
   * it does not, the notifications do nothing.
   *
   * @return {@code true} if the application runs under systemd
   */
  public static boolean isUnderSystemd() {
    return SystemdUtilities.isUnderSystemd();
  }

  /**
   * Returns the watchdog timeout of the service unit, that is, the <code>WatchdogSec</code> it sets, which systemd passes to the application in the
   * <code>WATCHDOG_USEC</code> environment variable.
   *
   * @return the watchdog timeout, or empty if the variable is not set, as when the service unit does not enable the watchdog, or not running under systemd
   */
  public static Optional<Duration> watchdogTimeout() {
    long watchdogUsec = SystemdUtilities.watchdogUsec();
    return watchdogUsec > 0 ? Optional.of(Duration.of(watchdogUsec, ChronoUnit.MICROS)) : Optional.empty();
  }

  /**
   * Logs information regarding the status of the integration with systemd. Meant to be used once the application has done sufficient work to initialize
   * logging.
   *
   * <p>When running under systemd, logs the notify socket and the periodic tasks that are enabled. Otherwise, logs why not: a warning with the cause when
   * systemd expects notifications, but they could not be set up, as when the native libsystemd library cannot be loaded on Java 17 to 21, since systemd
   * then receives no notifications, or a debug message otherwise.
   */
  public void logStatus() {
    if (isUnderSystemd()) {
      logger.info("Running under systemd, OS name: \"{}\", notify socket: \"{}\", notifications sent through: {}", osName(), notifySocketPath(),
          SystemdNotify.channel());
      logger.info("Enabled Systemd integration with options=({})", options());
    } else {
      Optional<Throwable> failure = SystemdNotify.loadFailure();
      if (failure.isPresent()) {
        logger.warn("Not running under systemd, as the notifications to systemd could not be set up, OS name: \"{}\", notify socket: \"{}\"", osName(),
            notifySocketPath(), failure.get());
      } else {
        logger.debug("Not running under systemd, OS name: \"{}\"", osName());
      }
    }
  }

  private String options() {
    scheduleLock.lock();
    try {
      Duration statusUpdatePeriod = statusUpdateTask.period;
      Duration extendTimeoutPeriod = extendTimeoutTask.period;
      Duration watchdogPeriod = watchdogTask.period;
      String statusUpdateOptions = statusUpdatePeriod != null
          ? String.format(Locale.ROOT, "statusUpdatePeriod=%s", statusUpdatePeriod)
          : "statusUpdate=disabled";
      String extendTimeoutOptions = extendTimeoutPeriod != null
          ? String.format(Locale.ROOT, "extendTimeoutPeriod=%s, extendTimeoutAmount=%s", extendTimeoutPeriod,
              Duration.of(extendTimeoutAmount, ChronoUnit.MICROS))
          : "extendTimeout=disabled";
      String watchdogOptions = watchdogPeriod != null
          ? String.format(Locale.ROOT, "watchdogPeriod=%s", watchdogPeriod)
          : "watchdog=disabled";
      return String.join(", ", statusUpdateOptions, extendTimeoutOptions, watchdogOptions);
    } finally {
      scheduleLock.unlock();
    }
  }

  /**
   * Stops the periodic tasks of this instance: the status updates, the watchdog heartbeats and the periodic extension of the timeout. Waits for running
   * tasks to finish, for up to 10 seconds each, before interrupting them. Status and health providers must therefore not call this method, as they run
   * within those tasks.
   *
   * <p>As the watchdog heartbeats stop, this method first calls {@link #stopping()}, if not called before, so that systemd stops enforcing the
   * <code>WatchdogSec</code> of the service unit, and does not consider the application hung, and kill it, if the rest of its shutdown takes longer than
   * that. Calling {@link #stopping()} as soon as the application starts shutting down is still recommended, as systemd keeps enforcing the watchdog until
   * then.
   *
   * <p>As systemd is told that the application is stopping, only close the instance when the application shuts down. A closed instance cannot be reopened:
   * {@link #instance()} keeps returning it, and enabling periodic tasks has no effect.
   *
   * <p>Notifications sent directly to systemd, such as {@link #updateStatus()}, keep working once closed, as they do not depend on the periodic tasks. This
   * allows the application to keep notifying systemd while shutting down. {@link #watchdog()} no longer sends heartbeats, as the application is stopping.
   *
   * <p>Calling this method more than once only waits, as the first call does, for the running tasks to finish: every call returns once the periodic tasks
   * have stopped, even when it races with another call.
   *
   * @throws InterruptedException if interrupted while waiting for the running tasks to finish
   */
  public void close() throws InterruptedException {
    // told before the heartbeats stop, for systemd not to kill the application if the rest of its shutdown takes longer than the watchdog interval
    stopping();
    scheduleLock.lock();
    try {
      // only the first call shuts the executors down; later or concurrent calls only wait for them to terminate, as the first call does
      if (!closed) {
        // set together with the shutdown, so that no periodic task can be enabled in between, on an executor that would reject it
        closed = true;
        // all are shut down before waiting on any, so that they wind down at the same time; shutting down also cancels their periodic tasks, as by
        // default periodic tasks do not keep running after shutdown
        statusExecutor.shutdown();
        watchdogExecutor.shutdown();
        extendTimeoutExecutor.shutdown();
      }
    } finally {
      scheduleLock.unlock();
    }
    // waited for by every call, without holding the lock, so that each returns once the periodic tasks have stopped, and so that running tasks that enable
    // or disable periodic tasks can finish
    awaitTermination(statusExecutor);
    awaitTermination(watchdogExecutor);
    awaitTermination(extendTimeoutExecutor);
  }

  // for tests: if this instance is closed
  boolean isClosed() {
    scheduleLock.lock();
    try {
      return closed;
    } finally {
      scheduleLock.unlock();
    }
  }

  /**
   * A periodic task, which can be scheduled at a period, rescheduled at another one, or cancelled. Guarded by the schedule lock of its instance.
   */
  private static final class PeriodicTask {

    private final ScheduledExecutorService executor;
    private final String name;
    private final Runnable task;
    // null if not scheduled
    @Nullable
    private ScheduledFuture<?> future;
    // null if not scheduled
    @Nullable
    private Duration period;

    private PeriodicTask(ScheduledExecutorService executor, String name, Runnable task) {
      this.executor = executor;
      this.name = name;
      this.task = task;
    }

    // a run of the previous schedule that is still running finishes before the first run of the new one, as the executor runs a single task at a time
    private void schedule(Duration period) {
      cancel();
      this.future = SystemdUtilities.schedule(executor, name, task, period, period);
      this.period = period;
    }

    private void cancel() {
      if (future != null) {
        future.cancel(false);
        future = null;
        period = null;
      }
    }
  }
}
