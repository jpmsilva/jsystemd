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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ActuatorHealthStatusProvider;
import com.github.jpmsilva.jsystemd.spring.AvailabilityStatusProvider;
import com.github.jpmsilva.jsystemd.spring.HikariStatusProvider;
import com.github.jpmsilva.jsystemd.spring.JettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.LiquibaseStatusProvider;
import com.github.jpmsilva.jsystemd.spring.NettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ShutdownStatusProvider;
import com.github.jpmsilva.jsystemd.spring.SpringProviders;
import com.github.jpmsilva.jsystemd.spring.SystemdLifecycle;
import com.github.jpmsilva.jsystemd.spring.TaskExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TomcatStatusProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Tests for {@link SystemdAutoConfiguration}.
 *
 * @author Joao Silva
 */
class SystemdAutoConfigurationTest {

  private static final long WATCHDOG_USEC = 10_000_000L;

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(SystemdAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class))
      // each context acts as if it were a process of its own, as a test may start several
      .withInitializer(context -> SystemdProcess.reset());

  private MockedStatic<?> utilities;

  @BeforeEach
  void underSystemd() {
    utilities = SystemdProcess.mockEnvironment();
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(true);
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
  }

  @AfterEach
  void close() {
    utilities.close();
  }

  @Test
  void nothingIsConfiguredWhenNotUnderSystemd() {
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(false);

    runner.run(context -> {
      assertThat(context).doesNotHaveBean(StatusProvider.class);
      assertThat(context).doesNotHaveBean(SystemdLifecycle.class);
    });
  }

  @Test
  void readyOnceAcceptingTrafficByDefault() {
    runner.run(context -> {
      AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

      assertThat(Systemd.instance().isReady()).isFalse();

      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);

      assertThat(Systemd.instance().isReady()).isTrue();
    });
  }

  @Test
  void notReadyOnceAcceptingTrafficWhenReadyOnceStarted() {
    // the run listener tells systemd once the application has started instead
    runner.withPropertyValues("systemd.ready-event=application-started").run(context -> {
      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);

      assertThat(Systemd.instance().isReady()).isFalse();
    });
  }

  /**
   * The status providers enabled by default, in the order of the systemd status: the Liquibase one first, as it is registered as soon as it is created,
   * to report the migrations run on startup, and then the beans, in their order.
   */
  private static final List<String> DEFAULT_STATUS_PROVIDERS = List.of("LiquibaseStatusProvider", "ShutdownStatusProvider", "PhaseStatusProvider",
      "ActuatorHealthStatusProvider", "AvailabilityStatusProvider", "HeapStatusProvider", "OldGenerationStatusProvider", "CgroupMemoryStatusProvider",
      "HikariStatusProvider", "ThreadStatusProvider", "FileDescriptorStatusProvider", "TomcatStatusProvider", "JettyStatusProvider", "NettyStatusProvider",
      "TaskExecutorStatusProvider", "NonHeapStatusProvider", "BufferPoolStatusProvider", "ClassLoaderStatusProvider");

  @Test
  void allBuiltInStatusProvidersAreEnabledByDefaultExceptTheOptedIntoOnes() {
    runner.run(context -> {
      assertThat(context).hasBean("systemdHeapStatusProvider");
      assertThat(context).hasBean("systemdNonHeapStatusProvider");
      assertThat(context).hasBean("systemdClassLoaderStatusProvider");
      assertThat(context).hasBean("systemdThreadStatusProvider");
      assertThat(context).hasBean("systemdTomcatStatusProvider");
      assertThat(context).hasBean("systemdActuatorHealthStatusProvider");
      assertThat(context).doesNotHaveBean("systemdGarbageCollectionStatusProvider");
      assertThat(context).doesNotHaveBean("systemdCpuStatusProvider");
      assertThat(Systemd.instance().getStatusProviders()).extracting(p -> p.getClass().getSimpleName())
          .containsExactlyElementsOf(DEFAULT_STATUS_PROVIDERS);
    });
  }

  @ParameterizedTest
  @CsvSource({
      "garbage-collection, systemdGarbageCollectionStatusProvider, GarbageCollectionStatusProvider, TaskExecutorStatusProvider",
      "cpu, systemdCpuStatusProvider, CpuStatusProvider, TaskExecutorStatusProvider"
  })
  void optedIntoStatusProvidersCanBeEnabled(String property, String beanName, String providerClass, String previousProviderClass) {
    runner.withPropertyValues("systemd.status-providers." + property + ".enabled=true").run(context -> {
      assertThat(context).hasBean(beanName);
      List<String> providers = Systemd.instance().getStatusProviders().stream().map(p -> p.getClass().getSimpleName()).toList();
      // in its place, after the one it follows
      assertThat(providers.indexOf(providerClass)).isEqualTo(providers.indexOf(previousProviderClass) + 1);
    });
  }

  @Test
  void keepsRegisteredStatusProvidersAndAddsTheBeansAfterThem() {
    StatusProvider registered = provider("registered");
    // also a bean, as the application context status provider registered by the run listener
    StatusProvider registeredBean = provider("registered bean");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SystemdAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class))
        .withInitializer(context -> Systemd.instance().addStatusProviders(registered, registeredBean))
        .withBean("registeredBean", StatusProvider.class, () -> registeredBean)
        .run(context -> {
          List<StatusProvider> providers = Systemd.instance().getStatusProviders();
          // the registered ones first, in their order, then the beans in their order, without adding a registered one again
          assertThat(providers).startsWith(registered, registeredBean, context.getBean(LiquibaseStatusProvider.class),
              context.getBean(ShutdownStatusProvider.class), context.getBean(PhaseStatusProvider.class),
              context.getBean(ActuatorHealthStatusProvider.class));
          assertThat(providers).endsWith(context.getBean("systemdClassLoaderStatusProvider", StatusProvider.class)).doesNotHaveDuplicates();
        });
  }

  private static StatusProvider provider(String status) {
    return new StatusProvider() {
      @Override
      public @NonNull String status() {
        return status;
      }
    };
  }

  @ParameterizedTest
  @CsvSource({
      "shutdown, systemdShutdownStatusProvider, ShutdownStatusProvider",
      "heap, systemdHeapStatusProvider, HeapStatusProvider",
      "old-generation, systemdOldGenerationStatusProvider, OldGenerationStatusProvider",
      "non-heap, systemdNonHeapStatusProvider, NonHeapStatusProvider",
      "buffer-pools, systemdBufferPoolStatusProvider, BufferPoolStatusProvider",
      "class-loader, systemdClassLoaderStatusProvider, ClassLoaderStatusProvider",
      "threads, systemdThreadStatusProvider, ThreadStatusProvider",
      "file-descriptors, systemdFileDescriptorStatusProvider, FileDescriptorStatusProvider",
      "cgroup-memory, systemdCgroupMemoryStatusProvider, CgroupMemoryStatusProvider",
      "availability, systemdAvailabilityStatusProvider, AvailabilityStatusProvider",
      "tomcat, systemdTomcatStatusProvider, TomcatStatusProvider",
      "jetty, systemdJettyStatusProvider, JettyStatusProvider",
      "netty, systemdNettyStatusProvider, NettyStatusProvider",
      "hikari, systemdHikariStatusProvider, HikariStatusProvider",
      "task-executors, systemdTaskExecutorStatusProvider, TaskExecutorStatusProvider",
      "liquibase, systemdLiquibaseStatusProvider, LiquibaseStatusProvider",
      "actuator-health, systemdActuatorHealthStatusProvider, ActuatorHealthStatusProvider"
  })
  void statusProviderCanBeDisabled(String property, String beanName, String providerClass) {
    runner.withPropertyValues("systemd.status-providers." + property + ".enabled=false").run(context -> {
      assertThat(context).doesNotHaveBean(beanName);
      assertThat(Systemd.instance().getStatusProviders()).extracting(p -> p.getClass().getSimpleName())
          .containsExactlyElementsOf(DEFAULT_STATUS_PROVIDERS.stream().filter(name -> !name.equals(providerClass)).toList());
    });
  }

  @Test
  void shutdownStagesAreRegisteredWithTheShutdownStatusProvider() {
    runner.run(context -> assertThat(context).hasBean("systemdShutdownRequestsStage").hasBean("systemdShutdownBeansStage"));
    runner.withPropertyValues("systemd.status-providers.shutdown.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean("systemdShutdownRequestsStage").doesNotHaveBean("systemdShutdownBeansStage"));
  }

  /**
   * The built-in status providers that the application can replace with its own, each with a supplier of an instance of the application.
   */
  static Stream<Arguments> replaceableStatusProviders() {
    return Stream.of(
        Arguments.of(ShutdownStatusProvider.class, (Supplier<StatusProvider>) () -> new ShutdownStatusProvider(new GenericApplicationContext())),
        Arguments.of(HeapStatusProvider.class, (Supplier<StatusProvider>) HeapStatusProvider::new),
        Arguments.of(OldGenerationStatusProvider.class, (Supplier<StatusProvider>) OldGenerationStatusProvider::new),
        Arguments.of(NonHeapStatusProvider.class, (Supplier<StatusProvider>) NonHeapStatusProvider::new),
        Arguments.of(BufferPoolStatusProvider.class, (Supplier<StatusProvider>) BufferPoolStatusProvider::new),
        Arguments.of(GarbageCollectionStatusProvider.class, (Supplier<StatusProvider>) GarbageCollectionStatusProvider::new),
        Arguments.of(ClassLoaderStatusProvider.class, (Supplier<StatusProvider>) ClassLoaderStatusProvider::new),
        Arguments.of(ThreadStatusProvider.class, (Supplier<StatusProvider>) () -> new ThreadStatusProvider(true, true)),
        Arguments.of(CgroupMemoryStatusProvider.class, (Supplier<StatusProvider>) CgroupMemoryStatusProvider::new),
        Arguments.of(AvailabilityStatusProvider.class, (Supplier<StatusProvider>) () -> new AvailabilityStatusProvider(true)),
        Arguments.of(TaskExecutorStatusProvider.class, (Supplier<StatusProvider>) () -> new TaskExecutorStatusProvider(new DefaultListableBeanFactory())),
        Arguments.of(CpuStatusProvider.class, (Supplier<StatusProvider>) CpuStatusProvider::new),
        Arguments.of(FileDescriptorStatusProvider.class, (Supplier<StatusProvider>) () -> new FileDescriptorStatusProvider(true, 0.5)),
        Arguments.of(TomcatStatusProvider.class, (Supplier<StatusProvider>) TomcatStatusProvider::new),
        Arguments.of(JettyStatusProvider.class, (Supplier<StatusProvider>) JettyStatusProvider::new),
        Arguments.of(NettyStatusProvider.class, (Supplier<StatusProvider>) NettyStatusProvider::new),
        Arguments.of(HikariStatusProvider.class,
            (Supplier<StatusProvider>) () -> new HikariStatusProvider(new DefaultListableBeanFactory(), true)));
  }

  @ParameterizedTest
  @MethodSource("replaceableStatusProviders")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void theApplicationCanReplaceABuiltInStatusProvider(Class<? extends StatusProvider> type, Supplier<StatusProvider> application) {
    // such as one configured differently, which the status would otherwise show along with the built-in one
    runner.withBean("applicationStatusProvider", (Class) type, (Supplier) application).run(context -> {
      assertThat(context.getBeansOfType(type)).containsOnlyKeys("applicationStatusProvider");
      assertThat(Systemd.instance().getStatusProviders()).filteredOn(type::isInstance).containsExactly(context.getBean("applicationStatusProvider", type));
    });
  }

  @Test
  void theApplicationCanProvideItsOwnPhaseStatusProvider() {
    PhaseStatusProvider phase = new PhaseStatusProvider();

    runner.withBean("customPhaseStatusProvider", PhaseStatusProvider.class, () -> phase)
        .run(context -> assertThat(context.getBean(PhaseStatusProvider.class)).isSameAs(phase));
  }

  @Test
  void actuatorHealthProviderReportsIndicatorsByBeanName() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.health=true").withBean("database", HealthIndicator.class, () -> () -> Health.down().withDetail("error", "failure").build())
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).health().details())
            .containsExactly(entry("database", Map.of("error", "failure"))));
  }

  @Test
  void enabledHealthStatusProvidersAreAlwaysHealthProviders() {
    runner.run(context -> assertThat(Systemd.instance().getHealthProviders()).containsExactlyInAnyOrder(
        context.getBean(ActuatorHealthStatusProvider.class), context.getBean(ThreadStatusProvider.class), context.getBean(FileDescriptorStatusProvider.class),
        context.getBean(HikariStatusProvider.class), context.getBean(AvailabilityStatusProvider.class)));
  }

  @Test
  void healthStatusProvidersAreHealthyByDefaultEvenWhenTheApplicationIsNot() {
    runner.withBean("database", HealthIndicator.class, () -> () -> Health.down().build()).run(context -> {
      ActuatorHealthStatusProvider actuator = context.getBean(ActuatorHealthStatusProvider.class);
      // the status shows the actual health, but the watchdog is not told about it without opting in
      assertThat(actuator.status()).isEqualTo("health status: unhealthy (database)");
      assertThat(actuator.health().healthy()).isTrue();
      assertThat(SystemdProcess.deadlockHealth(context.getBean(ThreadStatusProvider.class))).isFalse();
      assertThat(SystemdProcess.healthEnabled(context.getBean(FileDescriptorStatusProvider.class))).isFalse();
      assertThat(SpringProviders.healthEnabled(context.getBean(HikariStatusProvider.class))).isFalse();
      assertThat(SpringProviders.healthEnabled(context.getBean(AvailabilityStatusProvider.class))).isFalse();
    });
  }

  /**
   * The runner with the health endpoint of Spring Boot Actuator, which sets up its health groups.
   */
  private ApplicationContextRunner withHealthEndpoint() {
    return runner.withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class));
  }

  @Test
  void onlyTheMembersOfTheHealthGroupAreShownAndTakenIntoAccount() {
    withHealthEndpoint().withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.group=systemd",
            "management.endpoint.health.group.systemd.include=disk")
        .withBean("database", HealthIndicator.class, () -> () -> Health.down().build())
        .withBean("broker", HealthIndicator.class, () -> () -> Health.down().build())
        .withBean("disk", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> {
          ActuatorHealthStatusProvider actuator = context.getBean(ActuatorHealthStatusProvider.class);
          assertThat(actuator.status()).isEqualTo("health status: unhealthy (disk)");
          assertThat(actuator.health().details()).containsOnlyKeys("disk");
        });
  }

  @Test
  void theHealthGroupCanIncludeMembersOfAComposite() {
    withHealthEndpoint().withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.group=systemd",
            "management.endpoint.health.group.systemd.include=db/primary")
        .withBean("db", HealthContributor.class, () -> CompositeHealthContributor.fromMap(Map.of(
            "primary", (HealthIndicator) () -> Health.down().withDetail("error", "primary").build(),
            "secondary", (HealthIndicator) () -> Health.down().withDetail("error", "secondary").build())))
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).health().details())
            .containsExactly(entry("db", Map.of("primary", Map.of("error", "primary")))));
  }

  @Test
  void theLivenessGroupLeavesOutExternalSystems() {
    // the liveness and readiness groups, which Spring Boot sets up by default
    withHealthEndpoint().withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
            AvailabilityHealthContributorAutoConfiguration.class, AvailabilityProbesAutoConfiguration.class))
        .withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.group=liveness")
        .withBean("database", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> {
          ActuatorHealthStatusProvider actuator = context.getBean(ActuatorHealthStatusProvider.class);
          // as Spring Boot does once the application context is refreshed: the liveness state is broken until then
          AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);

          // the database is down, but it is not a member of the liveness group
          assertThat(actuator.status()).isEqualTo("health status: healthy");
          assertThat(actuator.health().healthy()).isTrue();

          AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);

          assertThat(actuator.status()).isEqualTo("health status: unhealthy (livenessState)");
          assertThat(actuator.health().healthy()).isFalse();
        });
  }

  @Test
  void failsWithAHealthGroupThatDoesNotExist() {
    withHealthEndpoint().withPropertyValues("systemd.status-providers.actuator-health.group=missing", "management.endpoint.health.group.systemd.include=*")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers.actuator-health.group")
            .hasMessageContaining("[systemd]"));
  }

  @Test
  void failsWithTheLivenessGroupWhenTheProbesAreDisabled() {
    withHealthEndpoint().withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
            AvailabilityHealthContributorAutoConfiguration.class, AvailabilityProbesAutoConfiguration.class))
        .withPropertyValues("systemd.status-providers.actuator-health.group=liveness", "management.endpoint.health.probes.enabled=false")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("management.endpoint.health.probes.enabled=false"));
  }

  @Test
  void failsWithAHealthGroupWithoutTheHealthEndpoint() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.group=systemd")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("health endpoint"));
  }

  @Test
  void anEmptyHealthGroupTakesAllHealthContributorsIntoAccount() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.group=")
        .withBean("database", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).status()).isEqualTo("health status: unhealthy (database)"));
  }

  @Test
  void usesTheNamesOfTheHealthEndpoint() {
    runner.withBean("dbHealthContributor", HealthContributor.class, () -> (HealthIndicator) () -> Health.down().build())
        .withBean("diskSpaceHealthIndicator", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).status()).isEqualTo("health status: unhealthy (db, diskSpace)"));
  }

  @Test
  void takesIntoAccountContributorsDeclaredAsHealthContributorRegardlessOfTheirCreationOrder() {
    // as the data source and Redis contributors of Spring Boot, declared as HealthContributor, by an autoconfiguration that may come after this one
    runner.withConfiguration(AutoConfigurations.of(LateHealthContributorConfiguration.class))
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).status()).isEqualTo("health status: unhealthy (db)"));
  }

  @Test
  void takesTheReactiveContributorsIntoAccount() {
    ReactiveHealthContributorRegistry reactiveRegistry = Mockito.mock(ReactiveHealthContributorRegistry.class);
    Mockito.when(reactiveRegistry.asHealthContributors()).thenReturn(CompositeHealthContributor.fromMap(Map.of("redis",
        (HealthIndicator) () -> Health.down().build())));

    runner.withBean(ReactiveHealthContributorRegistry.class, () -> reactiveRegistry)
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).status()).isEqualTo("health status: unhealthy (redis)"));
  }

  @Test
  void warnsWhenThereIsNoHealthContributorRegistry() {
    List<String> warnings = warnings(new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SystemdAutoConfiguration.class))
        .withInitializer(context -> SystemdProcess.reset()));

    assertThat(warnings).singleElement().asString().contains("no health contributor registry");
  }

  @AutoConfiguration(after = {SystemdAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class})
  static class LateHealthContributorConfiguration {

    @Bean
    HealthContributor dbHealthContributor() {
      return (HealthIndicator) () -> Health.down().build();
    }
  }

  @Test
  void actuatorHealthIsSignalledWhenOptedInto() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.health=true").withBean("database", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).health().healthy()).isFalse());
  }

  @Test
  void actuatorHealthDelaysUnhealthyStatusWhenDelayIsSet() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.unhealthy-delay=1m")
        .withBean("database", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> {
          ActuatorHealthStatusProvider actuator = context.getBean(ActuatorHealthStatusProvider.class);
          assertThat(actuator.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
          // the database is down, but reported as healthy to the watchdog until the delay elapses, while the status shows the actual health
          assertThat(actuator.health().healthy()).isTrue();
          assertThat(actuator.status()).isEqualTo("health status: unhealthy (database)");
        });
  }

  @Test
  void actuatorHealthHasNoDelayByDefault() {
    runner.run(context -> assertThat(context.getBean(ActuatorHealthStatusProvider.class).getUnhealthyDelay()).isNull());
  }

  @Test
  void actuatorHealthDelayDoesNotApplyToOtherHealthProviders() {
    DelayableHealthProvider custom = new DelayableHealthProvider() {
      @Override
      protected HealthProvider.Health evaluateHealth() {
        return HealthProvider.Health.HEALTHY;
      }
    };

    runner.withPropertyValues("systemd.status-providers.actuator-health.unhealthy-delay=1m").withBean("customHealthProvider", HealthProvider.class, () -> custom)
        .run(context -> {
          assertThat(Systemd.instance().getHealthProviders()).contains(custom);
          assertThat(custom.getUnhealthyDelay()).isNull();
        });
  }

  @Test
  void watchdogAddsHealthProvidersOfTheApplicationToTheBuiltInOnes() {
    HealthProvider custom = () -> HealthProvider.Health.HEALTHY;

    runner.withBean("customHealthProvider", HealthProvider.class, () -> custom).run(context ->
        assertThat(Systemd.instance().getHealthProviders()).containsExactlyInAnyOrder(custom,
            context.getBean(ActuatorHealthStatusProvider.class), context.getBean(ThreadStatusProvider.class),
            context.getBean(FileDescriptorStatusProvider.class), context.getBean(HikariStatusProvider.class),
            context.getBean(AvailabilityStatusProvider.class)));
  }

  @Test
  void keepsRegisteredHealthProvidersAndAddsTheBeansAfterThem() {
    HealthProvider registered = () -> HealthProvider.Health.HEALTHY;
    HealthProvider registeredBean = () -> HealthProvider.Health.HEALTHY;
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SystemdAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class))
        .withPropertyValues("systemd.status-providers.actuator-health.enabled=false")
        .withInitializer(context -> Systemd.instance().addHealthProviders(registered, registeredBean))
        .withBean("registeredBean", HealthProvider.class, () -> registeredBean)
        .run(context -> assertThat(Systemd.instance().getHealthProviders()).startsWith(registered, registeredBean)
            .containsExactlyInAnyOrder(registered, registeredBean, context.getBean(ThreadStatusProvider.class),
                context.getBean(FileDescriptorStatusProvider.class), context.getBean(HikariStatusProvider.class),
                context.getBean(AvailabilityStatusProvider.class)));
  }

  @Test
  void deadlocksAreNeitherDetectedNorSignalledByDefault() {
    runner.run(context -> {
      ThreadStatusProvider threads = context.getBean(ThreadStatusProvider.class);
      assertThat(SystemdProcess.detectDeadlocks(threads)).isFalse();
      assertThat(SystemdProcess.deadlockHealth(threads)).isFalse();
    });
  }

  @ParameterizedTest
  @CsvSource({
      "true, false",
      "true, true"
  })
  void startsWithDeadlockDetectionWithOrWithoutDeadlockHealth(boolean detection, boolean health) {
    // the combination rejected by the properties, deadlock health without detection, is tested below and in SystemdThreadStatusProviderPropertiesTest
    runner.withPropertyValues("systemd.status-providers.threads.deadlock-detection=" + detection, "systemd.status-providers.threads.deadlock-health=" + health)
        .run(context -> {
          assertThat(context).hasNotFailed();
          ThreadStatusProvider threads = context.getBean(ThreadStatusProvider.class);
          assertThat(SystemdProcess.detectDeadlocks(threads)).isEqualTo(detection);
          assertThat(SystemdProcess.deadlockHealth(threads)).isEqualTo(health);
        });
  }

  @Test
  void failsWhenDeadlockHealthIsEnabledWithoutDeadlockDetection() {
    runner.withPropertyValues("systemd.status-providers.threads.deadlock-health=true")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers.threads.deadlock-health")
            .hasMessageContaining("requires detecting them"));
  }

  @Test
  void noThreadHealthProviderWhenThreadStatusProviderIsDisabled() {
    runner.withPropertyValues("systemd.status-providers.threads.enabled=false").run(context ->
        assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ThreadStatusProvider.class).hasSize(4));
  }

  @Test
  void noActuatorHealthProviderWhenActuatorStatusProviderIsDisabled() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.enabled=false").run(context ->
        assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ActuatorHealthStatusProvider.class).hasSize(4));
  }

  @Test
  void actuatorHealthProviderRequiresActuator() {
    runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class)).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(ActuatorHealthStatusProvider.class);
      assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ActuatorHealthStatusProvider.class).hasSize(4);
    });
  }

  @Test
  void warnsWhenActuatorHealthIsSignalledWithoutUnhealthyStatusCodes() {
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(WATCHDOG_USEC);

    List<String> warnings = warnings(runner.withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.unhealthy-status-codes="));

    assertThat(warnings).singleElement().asString()
        .contains("will never be suppressed")
        .contains("systemd.status-providers.actuator-health.unhealthy-status-codes")
        .contains("systemd.watchdog-suppress-warning=true");
  }

  @Test
  void doesNotWarnAboutUnhealthyStatusCodesWithoutActuatorHealthSignal() {
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(WATCHDOG_USEC);

    assertThat(warnings(runner.withPropertyValues("systemd.status-providers.actuator-health.unhealthy-status-codes="))).isEmpty();
  }

  @Test
  void doesNotWarnWhenWarningsAreSuppressed() {
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(WATCHDOG_USEC);

    assertThat(warnings(runner.withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.unhealthy-status-codes=",
        "systemd.watchdog-suppress-warning=true"))).isEmpty();
  }

  @Test
  void doesNotWarnWhenWatchdogIsNotEnabledBySystemd() {
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(0L);

    assertThat(logged(runner.withPropertyValues("systemd.status-providers.actuator-health.health=true", "systemd.status-providers.actuator-health.unhealthy-status-codes=")))
        .noneMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  void notifiesReadyOnceTheApplicationAcceptsTraffic() {
    runner.run(context -> {
      Systemd systemd = Systemd.instance();

      AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
      assertThat(systemd.isReady()).isFalse();

      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
      assertThat(systemd.isReady()).isTrue();
    });
  }

  @Test
  void fileDescriptorHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.file-descriptors.health=true", "systemd.status-providers.file-descriptors.threshold=0.75",
        "systemd.status-providers.file-descriptors.unhealthy-delay=30s").run(context -> {
          FileDescriptorStatusProvider provider = context.getBean(FileDescriptorStatusProvider.class);
          assertThat(SystemdProcess.healthEnabled(provider)).isTrue();
          assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.75);
          assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
        });
  }

  @Test
  void fileDescriptorHealthHasAThresholdOf90PercentAndNoDelayByDefault() {
    runner.run(context -> {
      FileDescriptorStatusProvider provider = context.getBean(FileDescriptorStatusProvider.class);
      assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.9);
      assertThat(provider.getUnhealthyDelay()).isNull();
    });
  }

  @ParameterizedTest
  @CsvSource({"0", "1.5"})
  void failsWithAFileDescriptorThresholdOutOfRange(String threshold) {
    runner.withPropertyValues("systemd.status-providers.file-descriptors.threshold=" + threshold)
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers.file-descriptors.threshold"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"availability", "file-descriptors", "hikari", "actuator-health"})
  void failsWithANegativeUnhealthyDelay(String key) {
    runner.withPropertyValues("systemd.status-providers." + key + ".unhealthy-delay=-1s")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers." + key + ".unhealthy-delay"));
  }

  @Test
  void livenessHealthHasNoDelayByDefault() {
    runner.run(context -> assertThat(context.getBean(AvailabilityStatusProvider.class).getUnhealthyDelay()).isNull());
  }

  @Test
  void livenessHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.availability.health=true", "systemd.status-providers.availability.unhealthy-delay=30s")
        .run(context -> {
          AvailabilityStatusProvider provider = context.getBean(AvailabilityStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(provider)).isTrue();
          assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
        });
  }

  @Test
  void aBrokenLivenessStateIsSignalledWhenOptedInto() {
    runner.withPropertyValues("systemd.status-providers.availability.health=true").run(context -> {
      AvailabilityStatusProvider provider = context.getBean(AvailabilityStatusProvider.class);

      AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);

      assertThat(provider.health().healthy()).isFalse();
      assertThat(provider.status()).isEqualTo("Liveness: broken");
    });
  }

  @Test
  void hikariHealthIsDelayedByOneMinuteByDefault() {
    runner.run(context -> assertThat(context.getBean(HikariStatusProvider.class).getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1)));
  }

  @Test
  void hikariHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.hikari.health=true", "systemd.status-providers.hikari.unhealthy-delay=0").run(context -> {
      HikariStatusProvider provider = context.getBean(HikariStatusProvider.class);
      assertThat(SpringProviders.healthEnabled(provider)).isTrue();
      assertThat(provider.getUnhealthyDelay()).isZero();
    });
  }

  @ParameterizedTest
  @CsvSource({
      "org.eclipse.jetty.server.Server, JettyStatusProvider",
      "reactor.netty.http.server.HttpServer, NettyStatusProvider",
      "com.zaxxer.hikari.HikariDataSource, HikariStatusProvider",
      "liquibase.integration.spring.SpringLiquibase, LiquibaseStatusProvider",
      "org.apache.catalina.startup.Tomcat, TomcatStatusProvider"
  })
  void statusProvidersOfLibrariesRequireTheLibrary(String libraryClass, String providerClass) {
    runner.withClassLoader(new FilteredClassLoader(libraryClass)).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(Systemd.instance().getStatusProviders()).extracting(p -> p.getClass().getSimpleName()).doesNotContain(providerClass);
    });
  }

  @Test
  void reportsTheLiquibaseMigrationsAndTheConnectionPoolOfSpringBoot() {
    try (MockedStatic<SystemdNotify> notify = Mockito.mockStatic(SystemdNotify.class)) {
      runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class))
          .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID(), "spring.liquibase.change-log=classpath:db/changelog.sql")
          .run(context -> {
            assertThat(context).hasNotFailed();
            // the migrations ran while the context started, and were reported as they did
            notify.verify(SystemdProcess.status(() -> Mockito.contains("Liquibase: applying changeset create-users (0 applied)")));
            notify.verify(SystemdProcess.status(() -> Mockito.contains("Liquibase: applying changeset index-orders (2 applied)")));
            assertThat(context.getBean(LiquibaseStatusProvider.class).status()).isEmpty();
            // the pool was started by the migrations, and all its connections were given back
            assertThat(context.getBean(HikariStatusProvider.class).status()).isEqualTo("dataSource: 0/10 connections, 0 waiting");
          });
    }
  }

  private static List<String> warnings(ApplicationContextRunner runner) {
    return logged(runner).stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static List<ILoggingEvent> logged(ApplicationContextRunner runner) {
    Logger logger = (Logger) LoggerFactory.getLogger(SystemdAutoConfiguration.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      runner.run(context -> assertThat(context).hasNotFailed());
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list;
  }
}
