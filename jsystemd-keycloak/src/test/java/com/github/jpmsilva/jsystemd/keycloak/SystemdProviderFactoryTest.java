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
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupPressureStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupTasksStatusProvider;
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.VirtualThreadStatusProvider;
import io.agroal.api.AgroalDataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.provider.Spi;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.storage.ImportRealmFromRepresentationEvent;
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
  // the datasource, which there is none of by default, as outside of Quarkus
  private final AtomicReference<@Nullable AgroalDataSource> dataSource = new AtomicReference<>();
  private final SystemdProviderFactory factory = new SystemdProviderFactory(listening::get, this::checks, dataSource::get);

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
    return startKeycloak(factory);
  }

  private ProviderEventListener startKeycloak(SystemdProviderFactory factory) {
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

  @ParameterizedTest
  @ValueSource(strings = {"status-update-period-seconds", "datasource-unhealthy-delay-seconds"})
  void ignoresANumberOfSecondsThatIsNotANumber(String option) {
    when(config.getInt(eq(option), any())).thenThrow(new NumberFormatException("For input string: \"soon\""));
    when(config.getBoolean(eq("datasource-health"), any())).thenReturn(true);

    assertThat(SystemdProviderFactory.statusUpdatePeriod(config)).isEqualTo(Duration.ofSeconds(5));
    assertThat(factory.providers(config).health()).singleElement().asInstanceOf(type(DatasourceStatusProvider.class))
        .extracting(DatasourceStatusProvider::getUnhealthyDelay).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void enablesTheDefaultStatusProviders() {
    assertThat(factory.providers(config).status()).hasSize(10)
        .hasOnlyElementsOfTypes(ClusterStatusProvider.class, HeapStatusProvider.class, OldGenerationStatusProvider.class,
            CgroupMemoryStatusProvider.class, DatasourceStatusProvider.class, ThreadStatusProvider.class, FileDescriptorStatusProvider.class,
            NonHeapStatusProvider.class, BufferPoolStatusProvider.class, ClassLoaderStatusProvider.class)
        .first().isInstanceOf(ClusterStatusProvider.class);
  }

  @Test
  void enablesTheConfiguredStatusProvidersInOrder() {
    when(config.getArray("status-providers")).thenReturn(new String[] {"version", "cpu", " threads", "unknown", "garbage-collection", "cluster"});

    assertThat(factory.providers(config).status()).hasExactlyElementsOfTypes(ClusterStatusProvider.class, ThreadStatusProvider.class,
        CpuStatusProvider.class, GarbageCollectionStatusProvider.class, VersionStatusProvider.class);
  }

  @Test
  void enablesTheStatusProvidersOfTheControlGroupAndOfTheVirtualThreadsInOrder() {
    when(config.getArray("status-providers")).thenReturn(new String[] {"cgroup-tasks", "virtual-threads", "cgroup-pressure", "cgroup-memory"});

    assertThat(factory.providers(config).status()).hasExactlyElementsOfTypes(CgroupMemoryStatusProvider.class, CgroupPressureStatusProvider.class,
        VirtualThreadStatusProvider.class, CgroupTasksStatusProvider.class);
  }

  @Test
  void knowsEveryStatusProviderOfItsOptions() {
    String known = factory.getConfigMetadata().stream().filter(property -> "status-providers".equals(property.getName())).findFirst().orElseThrow()
        .getHelpText().replaceFirst(".* among ", "").replace(".", "");
    when(config.getArray("status-providers")).thenReturn(known.split(", "));

    assertThat(factory.providers(config).status()).hasSize(16);
  }

  @Test
  void showsTheVersionOfKeycloak() {
    assertThat(new VersionStatusProvider().status()).isEqualTo("Keycloak " + Version.VERSION);
  }

  @Test
  void hasNoHealthProvidersByDefault() {
    assertThat(factory.providers(config).health()).isEmpty();
  }

  @Test
  void restartsOnDeadlocksWhenOptedInto() {
    when(config.getBoolean(eq("threads-deadlock-detection"), any())).thenReturn(true);
    when(config.getBoolean(eq("threads-deadlock-health"), any())).thenReturn(true);

    SystemdProviderFactory.Providers providers = factory.providers(config);

    // the same instance shows the deadlocked threads in the status
    assertThat(providers.health()).singleElement().isInstanceOf(ThreadStatusProvider.class).isIn(providers.status());
  }

  @Test
  void ignoresTheHealthOfDeadlocksWithoutDetectingThem() {
    when(config.getBoolean(eq("threads-deadlock-health"), any())).thenReturn(true);

    assertThat(factory.providers(config).health()).isEmpty();
  }

  @Test
  void restartsWhenTheFileDescriptorsAreAboutToRunOutWhenOptedInto() {
    when(config.getBoolean(eq("file-descriptors-health"), any())).thenReturn(true);
    when(config.get("file-descriptors-threshold")).thenReturn("0.5");
    when(config.getInt(eq("file-descriptors-unhealthy-delay-seconds"), any())).thenReturn(30);

    SystemdProviderFactory.Providers providers = factory.providers(config);

    assertThat(providers.health()).singleElement().isIn(providers.status()).asInstanceOf(type(FileDescriptorStatusProvider.class))
        .satisfies(fileDescriptors -> {
          assertThat(fileDescriptors.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
          // the limit of the file descriptors of the tests is not known, but half of it is far from reached
          assertThat(fileDescriptors.health().healthy()).isTrue();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1.5", "-0.1", "most"})
  void ignoresAThresholdOfFileDescriptorsOutOfRange(String threshold) {
    when(config.getBoolean(eq("file-descriptors-health"), any())).thenReturn(true);
    when(config.get("file-descriptors-threshold")).thenReturn(threshold);

    // not delayed by default
    assertThat(factory.providers(config).health()).singleElement().asInstanceOf(type(FileDescriptorStatusProvider.class))
        .extracting(FileDescriptorStatusProvider::getUnhealthyDelay).isNull();
  }

  @Test
  void restartsWhenTheTasksAreAboutToRunOutWhenOptedInto() {
    when(config.getBoolean(eq("cgroup-tasks-health"), any())).thenReturn(true);
    when(config.get("cgroup-tasks-threshold")).thenReturn("0.5");
    when(config.getInt(eq("cgroup-tasks-unhealthy-delay-seconds"), any())).thenReturn(30);

    SystemdProviderFactory.Providers providers = factory.providers(config);

    // taken into account although the tasks are not shown by default
    assertThat(providers.health()).singleElement().isNotIn(providers.status()).asInstanceOf(type(CgroupTasksStatusProvider.class))
        .extracting(CgroupTasksStatusProvider::getUnhealthyDelay).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void showsTheTasksThatAreTakenIntoAccount() {
    when(config.getBoolean(eq("cgroup-tasks-health"), any())).thenReturn(true);
    when(config.getArray("status-providers")).thenReturn(new String[] {"cgroup-tasks"});

    SystemdProviderFactory.Providers providers = factory.providers(config);

    // the same instance shows the tasks in the status, not delayed by default
    assertThat(providers.health()).singleElement().isIn(providers.status()).asInstanceOf(type(CgroupTasksStatusProvider.class))
        .extracting(CgroupTasksStatusProvider::getUnhealthyDelay).isNull();
  }

  @Test
  void restartsWhileCollectingGarbageMostOfTheTimeWhenOptedInto() {
    when(config.getBoolean(eq("garbage-collection-health"), any())).thenReturn(true);
    when(config.getArray("status-providers")).thenReturn(new String[] {"garbage-collection"});

    SystemdProviderFactory.Providers providers = factory.providers(config);

    // delayed by a minute by default, as a burst of allocations can also keep the garbage collector busy
    assertThat(providers.health()).singleElement().isIn(providers.status()).asInstanceOf(type(GarbageCollectionStatusProvider.class))
        .satisfies(garbageCollection -> {
          assertThat(garbageCollection.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
          assertThat(garbageCollection.health().healthy()).isTrue();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"cgroup-tasks", "garbage-collection"})
  void ignoresAThresholdOutOfRange(String key) {
    when(config.getBoolean(eq(key + "-health"), any())).thenReturn(true);
    when(config.get(key + "-threshold")).thenReturn("1.5");

    assertThat(factory.providers(config).health()).singleElement().isInstanceOfAny(CgroupTasksStatusProvider.class,
        GarbageCollectionStatusProvider.class);
  }

  @Test
  void restartsWhenTheDatasourceIsStarvedWhenOptedInto() {
    when(config.getBoolean(eq("datasource-health"), any())).thenReturn(true);

    SystemdProviderFactory.Providers providers = factory.providers(config);

    assertThat(providers.health()).singleElement().isIn(providers.status()).asInstanceOf(type(DatasourceStatusProvider.class))
        .satisfies(datasource -> {
          assertThat(datasource.healthEnabled()).isTrue();
          assertThat(datasource.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
        });
  }

  @Test
  void doesNotDelayTheHealthOfTheDatasourceWithADelayOfZero() {
    when(config.getBoolean(eq("datasource-health"), any())).thenReturn(true);
    when(config.getInt(eq("datasource-unhealthy-delay-seconds"), any())).thenReturn(0);

    assertThat(factory.providers(config).health()).singleElement().asInstanceOf(type(DatasourceStatusProvider.class))
        .extracting(DatasourceStatusProvider::getUnhealthyDelay).isNull();
  }

  @Test
  void takesTheHealthIntoAccountOfProvidersThatAreNotShown() {
    when(config.getArray("status-providers")).thenReturn(new String[] {"heap"});
    when(config.getBoolean(eq("datasource-health"), any())).thenReturn(true);

    SystemdProviderFactory.Providers providers = factory.providers(config);

    assertThat(providers.status()).singleElement().isInstanceOf(HeapStatusProvider.class);
    assertThat(providers.health()).singleElement().isInstanceOf(DatasourceStatusProvider.class);
  }

  @Test
  void onlyLooksUpTheDatasourceOnceKeycloakHasCompletedItsBootstrap() {
    AgroalDataSource pool = mock(AgroalDataSource.class, RETURNS_DEEP_STUBS);
    when(pool.getConfiguration().metricsEnabled()).thenReturn(true);
    when(pool.getConfiguration().connectionPoolConfiguration().maxSize()).thenReturn(100);
    AtomicInteger lookups = new AtomicInteger();
    SystemdProviderFactory factory = new SystemdProviderFactory(listening::get, this::checks, () -> {
      lookups.incrementAndGet();
      return pool;
    });
    ProviderEventListener listener = startKeycloak(factory);
    StatusProvider datasource = factory.providers(config).status().get(4);

    assertThat(datasource.status()).isEmpty();
    assertThat(lookups).hasValue(0);

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    assertThat(datasource.status()).isEqualTo("Datasource: 0/100 connections, 0 waiting");
    factory.close();
  }

  @Test
  void showsTheChangesetsOfTheDatabaseMigrationAndExtendsTheStartupTimeout() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    startKeycloak();

    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");

    assertThat(factory.phase()).isEqualTo("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");
    verify(systemd).extendTimeout();

    // the many changes of a changeset only extend the timeout once a second
    StartupProgress.step();
    StartupProgress.step();
    StartupProgress.stage("Startup: bootstrapping");

    assertThat(factory.phase()).isEqualTo("Startup: bootstrapping");
    verify(systemd).extendTimeout();

    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201-org-redirect-url, 5 applied)");

    verify(systemd, times(2)).extendTimeout();
  }

  @Test
  void extendsTheStartupTimeoutOnTheFirstSmallStep() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    startKeycloak();

    StartupProgress.step();

    assertThat(factory.phase()).isEqualTo("Startup: bootstrapping");
    verify(systemd).extendTimeout();
  }

  @Test
  void showsTheStepsOfTheBootstrapWithoutExtendingTheStartupTimeoutWhenDisabled() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    when(config.getBoolean(eq("startup-timeout-extension"), any())).thenReturn(false);
    startKeycloak();

    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");

    assertThat(factory.phase()).isEqualTo("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");
    verify(systemd, never()).extendTimeout();
  }

  @Test
  void showsTheRealmsItImportsAndExtendsTheStartupTimeout() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    ProviderEventListener listener = startKeycloak();
    RealmRepresentation realm = new RealmRepresentation();
    realm.setRealm("acme");

    listener.onEvent(new ImportRealmFromRepresentationEvent(mock(KeycloakSession.class), realm));

    assertThat(factory.phase()).isEqualTo("Startup: importing realm acme");
    verify(systemd).extendTimeout();

    // as for the realm, which is a step that may take long, whatever came right before it
    listener.onEvent(new ImportRealmFromRepresentationEvent(mock(KeycloakSession.class), new RealmRepresentation()));

    assertThat(factory.phase()).isEqualTo("Startup: importing a realm");
    verify(systemd, times(2)).extendTimeout();
  }

  @Test
  void extendsTheStartupTimeoutOnTheEventsOfTheBootstrap() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(mock(ProviderEvent.class));
    listener.onEvent(mock(ProviderEvent.class));

    assertThat(factory.phase()).isEqualTo("Startup: bootstrapping");
    verify(systemd).extendTimeout();
  }

  @Test
  void doesNotExtendTheStartupTimeoutOnceTheBootstrapCompletes() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    listening.set(false);
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(new PostMigrationEvent(sessionFactory));

    // what Keycloak waits for from then on is not progress
    listener.onEvent(mock(ProviderEvent.class));
    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");

    assertThat(factory.phase()).isNotEqualTo("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");
    verify(systemd, never()).extendTimeout();
    // ends the wait for the HTTP server
    listening.set(true);
    await().atMost(Duration.ofSeconds(5)).until(() -> factory.phase() == null);
  }

  @Test
  void doesNotExtendTheTimeoutOnceStopping() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    startKeycloak();
    factory.stopping();
    // the startup is over once stopping
    when(systemd.isReady()).thenReturn(true);

    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");

    // it would otherwise extend the shutdown
    assertThat(factory.phase()).isEqualTo("Shutdown: stopping");
    verify(systemd, never()).extendTimeout();
  }

  @Test
  void stopsFollowingTheBootstrapOnceClosed() {
    Systemd systemd = mock(Systemd.class);
    SystemdProcess.replace(systemd);
    startKeycloak();
    factory.close();

    StartupProgress.milestone("Startup: migrating the database (changeset 26.0.0-33201, 4 applied)");

    assertThat(factory.phase()).isEqualTo("Startup: bootstrapping");
    verify(systemd, never()).extendTimeout();
  }

  @Test
  void keepsRunningTheReadinessHealthChecksForTheWatchdogWhenOptedInto() {
    healthEnabled.set(true);
    healthy.set(true);
    when(config.getBoolean(eq("readiness-health"), any())).thenReturn(true);
    when(config.getInt(eq("readiness-unhealthy-delay-seconds"), any())).thenReturn(0);
    ProviderEventListener listener = startKeycloak();
    listener.onEvent(new PostMigrationEvent(sessionFactory));
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());

    HealthProvider readiness = Systemd.instance().getHealthProviders().stream().filter(ReadinessHealthProvider.class::isInstance).findFirst()
        .orElseThrow();
    assertThat(readiness.health().healthy()).isTrue();

    healthy.set(false);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(readiness.health())
        .isEqualTo(new HealthProvider.Health(false, Map.of("readinessHealthChecksDown", List.of("database")))));
  }

  @Test
  void delaysTheHealthOfTheReadinessHealthChecksForFiveMinutesByDefault() {
    healthEnabled.set(true);
    when(config.getBoolean(eq("readiness-health"), any())).thenReturn(true);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    assertThat(Systemd.instance().getHealthProviders()).singleElement().asInstanceOf(type(ReadinessHealthProvider.class))
        .extracting(ReadinessHealthProvider::getUnhealthyDelay).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void doesNotRunTheReadinessHealthChecksForTheWatchdogByDefault() {
    healthEnabled.set(true);
    healthy.set(true);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));
    await().atMost(Duration.ofSeconds(5)).until(() -> Systemd.instance().isReady());

    assertThat(Systemd.instance().getHealthProviders()).isEmpty();
  }

  @Test
  void hasNoHealthOfTheReadinessHealthChecksWithoutThem() {
    when(config.getBoolean(eq("readiness-health"), any())).thenReturn(true);
    ProviderEventListener listener = startKeycloak();

    listener.onEvent(new PostMigrationEvent(sessionFactory));

    assertThat(Systemd.instance().isReady()).isTrue();
    assertThat(Systemd.instance().getHealthProviders()).isEmpty();
  }

  @Test
  void isRegisteredAsAnSpi() {
    assertThat(ServiceLoader.load(Spi.class)).anySatisfy(spi -> assertThat(spi).isInstanceOf(SystemdSpi.class));
    assertThat(ServiceLoader.load(SystemdProviderFactory.class)).hasSize(1);
  }
}
