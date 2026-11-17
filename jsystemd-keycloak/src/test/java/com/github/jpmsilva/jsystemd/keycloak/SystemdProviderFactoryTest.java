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

package com.github.jpmsilva.jsystemd.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.provider.Spi;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link SystemdProviderFactory}.
 *
 * @author Joao Silva
 */
class SystemdProviderFactoryTest {

  private final Config.Scope config = mock(Config.Scope.class);
  private final KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
  // if the HTTP server listens: it does by default, as when Keycloak bootstraps once its HTTP server listens
  private final AtomicBoolean listening = new AtomicBoolean(true);
  // if the readiness health checks are all up
  private final AtomicBoolean healthy = new AtomicBoolean(false);
  // the readiness health checks that are down, while they are not all up
  private final AtomicReference<List<String>> down = new AtomicReference<>(List.of("database"));
  // if Keycloak has readiness health checks: it does not by default, as when its health endpoints are disabled
  private final AtomicBoolean healthEnabled = new AtomicBoolean(false);
  private final SystemdProviderFactory factory = new SystemdProviderFactory(listening::get, this::checks);

  private HealthReadiness.@Nullable Checks checks() {
    return healthEnabled.get() ? () -> healthy.get() ? null : down.get() : null;
  }

  @BeforeEach
  void setUp() {
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
    // the defaults, as Keycloak passes them when no option is set
    when(config.getInt(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(config.getBoolean(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
  }

  @AfterEach
  void tearDown() {
    factory.close();
  }

  private ProviderEventListener startKeycloak() {
    factory.init(config);
    factory.postInit(sessionFactory);
    ArgumentCaptor<ProviderEventListener> listener = ArgumentCaptor.forClass(ProviderEventListener.class);
    verify(sessionFactory).register(listener.capture());
    return listener.getValue();
  }

  @Test
  void reportsBootstrappingUntilTheBootstrapCompletes() {
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(mock(ProviderEvent.class));

    assertThat(factory.phase()).isEqualTo("Startup: bootstrapping");
    assertThat(Systemd.instance().isReady()).isFalse();
  }

  @Test
  void isReadyOnceTheBootstrapCompletes() {
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    assertThat(Systemd.instance().isReady()).isTrue();
    assertThat(factory.phase()).isNull();
  }

  @Test
  void isReadyOnceListeningWhenTheBootstrapCompletesFirst() {
    listening.set(false);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));
    assertThat(Systemd.instance().isReady()).isFalse();

    listening.set(true);
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());
    assertThat(factory.phase()).isNull();
  }

  @Test
  void stopsWaitingForTheHttpServerWhenStopping() {
    listening.set(false);
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(new PostMigrationEvent(sessionFactory));

    factory.stopping();
    listening.set(true);

    // the waiting thread does not clear the stopping stage
    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> "Shutdown: stopping".equals(factory.phase()));
  }

  @Test
  void isReadyOnceTheReadinessHealthChecksAreUp() {
    healthEnabled.set(true);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));
    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> !Systemd.instance().isReady());

    healthy.set(true);
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());
    assertThat(factory.phase()).isNull();
  }

  @Test
  void isReadyOnceListeningWhenTheReadinessHealthChecksAreUpFirst() {
    healthEnabled.set(true);
    healthy.set(true);
    listening.set(false);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));
    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> !Systemd.instance().isReady());

    listening.set(true);
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());
  }

  @Test
  void stopsWaitingForTheReadinessHealthChecksWhenStopping() {
    healthEnabled.set(true);
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(new PostMigrationEvent(sessionFactory));

    factory.stopping();
    healthy.set(true);

    // the waiting thread does not clear the stopping stage
    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> "Shutdown: stopping".equals(factory.phase()));
  }

  @Test
  void showsThatItWaitsForTheHttpServer() {
    listening.set(false);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    await().atMost(Duration.ofSeconds(5)).until(() -> "Startup: waiting for the HTTP server".equals(factory.phase()));
  }

  @Test
  void showsTheReadinessHealthChecksThatAreDown() {
    healthEnabled.set(true);
    down.set(List.of("database", "cache"));
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    await().atMost(Duration.ofSeconds(5)).until(() -> "Startup: waiting for the readiness health checks (down: database, cache)".equals(factory.phase()));
    down.set(List.of("cache"));
    await().atMost(Duration.ofSeconds(5)).until(() -> "Startup: waiting for the readiness health checks (down: cache)".equals(factory.phase()));
    healthy.set(true);
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());
    assertThat(factory.phase()).isNull();
  }

  @Test
  void showsThatItWaitsForTheReadinessHealthChecksWhenTheyCannotRun() {
    healthEnabled.set(true);
    down.set(List.of());
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    await().atMost(Duration.ofSeconds(5)).until(() -> "Startup: waiting for the readiness health checks".equals(factory.phase()));
  }

  @Test
  void hasNoReadinessHealthChecksOutsideOfQuarkus() {
    assertThat(HealthReadiness.lookup()).isNull();
  }

  @Test
  void reportsStopping() {
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(new PostMigrationEvent(sessionFactory));

    factory.stopping();

    assertThat(factory.phase()).isEqualTo("Shutdown: stopping");
  }

  @Test
  void keepsTheStoppingStageWhenStoppedWhileBootstrapping() {
    ProviderEventListener listener = startKeycloak();
    factory.stopping();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    assertThat(factory.phase()).isEqualTo("Shutdown: stopping");
  }

  @Test
  void stoppingWaitsForReadyToComplete() throws InterruptedException {
    startKeycloak();
    CountDownLatch sendingTheStatus = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread readying = new Thread(factory::ready);
    // blocks ready() as it sends the status, once it has cleared the stage of the startup
    Systemd.instance().addStatusProviders(new StatusProvider() {
      @Override
      public String status() {
        if (Thread.currentThread() == readying) {
          sendingTheStatus.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return "";
      }
    });
    readying.start();
    assertThat(sendingTheStatus.await(5, TimeUnit.SECONDS)).isTrue();

    Thread stopping = new Thread(factory::stopping);
    stopping.start();

    // stopping() waits for ready() to complete, rather than setting its stage meanwhile, which ready() could otherwise clear
    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> factory.phase() == null && stopping.isAlive());
    release.countDown();
    readying.join(5000);
    stopping.join(5000);
    assertThat(factory.phase()).isEqualTo("Shutdown: stopping");
  }

  @Test
  void sendsTheStatusEveryFiveSecondsByDefault() {
    assertThat(SystemdProviderFactory.statusUpdatePeriod(config)).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void sendsTheStatusAtTheConfiguredPeriod() {
    when(config.getInt(eq("status-update-period-seconds"), any())).thenReturn(30);

    assertThat(SystemdProviderFactory.statusUpdatePeriod(config)).isEqualTo(Duration.ofSeconds(30));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void ignoresAStatusUpdatePeriodThatIsNotPositive(int seconds) {
    when(config.getInt(eq("status-update-period-seconds"), any())).thenReturn(seconds);

    assertThat(SystemdProviderFactory.statusUpdatePeriod(config)).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void enablesTheDefaultStatusProviders() {
    assertThat(SystemdProviderFactory.statusProviders(config)).hasSize(8).first().isInstanceOf(HeapStatusProvider.class);
  }

  @Test
  void enablesTheConfiguredStatusProvidersInOrder() {
    when(config.getArray("status-providers")).thenReturn(new String[] {"cpu", " threads", "unknown", "garbage-collection"});

    assertThat(SystemdProviderFactory.statusProviders(config))
        .hasExactlyElementsOfTypes(ThreadStatusProvider.class, CpuStatusProvider.class, GarbageCollectionStatusProvider.class);
  }

  @Test
  void isRegisteredAsAnSpi() {
    assertThat(ServiceLoader.load(Spi.class)).anySatisfy(spi -> assertThat(spi).isInstanceOf(SystemdSpi.class));
    assertThat(ServiceLoader.load(SystemdProviderFactory.class)).hasSize(1);
  }
}
