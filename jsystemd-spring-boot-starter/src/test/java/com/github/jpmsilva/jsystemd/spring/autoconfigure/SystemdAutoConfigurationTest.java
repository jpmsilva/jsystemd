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
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupTasksStatusProvider;
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
import com.github.jpmsilva.jsystemd.spring.BuildInfoStatusProvider;
import com.github.jpmsilva.jsystemd.spring.FlywayStatusProvider;
import com.github.jpmsilva.jsystemd.spring.HikariStatusProvider;
import com.github.jpmsilva.jsystemd.spring.JettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.JmsListenerStatusProvider;
import com.github.jpmsilva.jsystemd.spring.KafkaListenerStatusProvider;
import com.github.jpmsilva.jsystemd.spring.LiquibaseStatusProvider;
import com.github.jpmsilva.jsystemd.spring.MicrometerStatusProvider;
import com.github.jpmsilva.jsystemd.spring.NettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.R2dbcPoolStatusProvider;
import com.github.jpmsilva.jsystemd.spring.RabbitListenerStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ScheduledTaskStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ShutdownStatusProvider;
import com.github.jpmsilva.jsystemd.spring.SpringProviders;
import com.github.jpmsilva.jsystemd.spring.SystemdLifecycle;
import com.github.jpmsilva.jsystemd.spring.TaskExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TomcatStatusProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
   * The status providers enabled by default, in the order of the systemd status: the Liquibase and Flyway ones first, as they are registered as soon as
   * they are created, to report the migrations run on startup, and then the beans, in their order.
   */
  private static final List<String> DEFAULT_STATUS_PROVIDERS = List.of("LiquibaseStatusProvider", "FlywayStatusProvider", "ShutdownStatusProvider",
      "PhaseStatusProvider",
      "ActuatorHealthStatusProvider", "AvailabilityStatusProvider", "HeapStatusProvider", "OldGenerationStatusProvider", "CgroupMemoryStatusProvider",
      "HikariStatusProvider", "ThreadStatusProvider", "FileDescriptorStatusProvider", "TomcatStatusProvider", "JettyStatusProvider", "NettyStatusProvider",
      "TaskExecutorStatusProvider", "KafkaListenerStatusProvider", "RabbitListenerStatusProvider", "JmsListenerStatusProvider", "NonHeapStatusProvider",
      "BufferPoolStatusProvider", "ClassLoaderStatusProvider");

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
      assertThat(context).doesNotHaveBean("systemdCgroupPressureStatusProvider");
      assertThat(context).doesNotHaveBean("systemdVirtualThreadStatusProvider");
      assertThat(context).doesNotHaveBean("systemdCgroupTasksStatusProvider");
      assertThat(Systemd.instance().getStatusProviders()).extracting(p -> p.getClass().getSimpleName())
          .containsExactlyElementsOf(DEFAULT_STATUS_PROVIDERS);
    });
  }

  @ParameterizedTest
  @CsvSource({
      "garbage-collection, systemdGarbageCollectionStatusProvider, GarbageCollectionStatusProvider, JmsListenerStatusProvider",
      "cpu, systemdCpuStatusProvider, CpuStatusProvider, JmsListenerStatusProvider",
      "r2dbc, systemdR2dbcPoolStatusProvider, R2dbcPoolStatusProvider, HikariStatusProvider",
      "cgroup-pressure, systemdCgroupPressureStatusProvider, CgroupPressureStatusProvider, CgroupMemoryStatusProvider",
      "virtual-threads, systemdVirtualThreadStatusProvider, VirtualThreadStatusProvider, ThreadStatusProvider",
      "cgroup-tasks, systemdCgroupTasksStatusProvider, CgroupTasksStatusProvider, FileDescriptorStatusProvider"
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
              context.getBean(FlywayStatusProvider.class), context.getBean(ShutdownStatusProvider.class), context.getBean(PhaseStatusProvider.class),
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
      "flyway, systemdFlywayStatusProvider, FlywayStatusProvider",
      "kafka-listeners, systemdKafkaListenerStatusProvider, KafkaListenerStatusProvider",
      "rabbit-listeners, systemdRabbitListenerStatusProvider, RabbitListenerStatusProvider",
      "jms-listeners, systemdJmsListenerStatusProvider, JmsListenerStatusProvider",
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
            (Supplier<StatusProvider>) () -> new HikariStatusProvider(new DefaultListableBeanFactory(), true)),
        Arguments.of(R2dbcPoolStatusProvider.class,
            (Supplier<StatusProvider>) () -> new R2dbcPoolStatusProvider(new DefaultListableBeanFactory(), true)),
        Arguments.of(KafkaListenerStatusProvider.class,
            (Supplier<StatusProvider>) () -> new KafkaListenerStatusProvider(new DefaultListableBeanFactory(), true)),
        Arguments.of(RabbitListenerStatusProvider.class,
            (Supplier<StatusProvider>) () -> new RabbitListenerStatusProvider(new DefaultListableBeanFactory(), true)),
        Arguments.of(JmsListenerStatusProvider.class,
            (Supplier<StatusProvider>) () -> new JmsListenerStatusProvider(new DefaultListableBeanFactory(), true)));
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
        context.getBean(HikariStatusProvider.class), context.getBean(KafkaListenerStatusProvider.class), context.getBean(RabbitListenerStatusProvider.class),
        context.getBean(JmsListenerStatusProvider.class), context.getBean(AvailabilityStatusProvider.class), context.getBean(TomcatStatusProvider.class),
        context.getBean(JettyStatusProvider.class)));
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
      assertThat(SpringProviders.healthEnabled(context.getBean(TomcatStatusProvider.class))).isFalse();
      assertThat(SpringProviders.healthEnabled(context.getBean(JettyStatusProvider.class))).isFalse();
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
            context.getBean(KafkaListenerStatusProvider.class), context.getBean(RabbitListenerStatusProvider.class),
            context.getBean(JmsListenerStatusProvider.class), context.getBean(AvailabilityStatusProvider.class),
            context.getBean(TomcatStatusProvider.class), context.getBean(JettyStatusProvider.class)));
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
                context.getBean(KafkaListenerStatusProvider.class), context.getBean(RabbitListenerStatusProvider.class),
                context.getBean(JmsListenerStatusProvider.class), context.getBean(AvailabilityStatusProvider.class),
                context.getBean(TomcatStatusProvider.class), context.getBean(JettyStatusProvider.class)));
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
        assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ThreadStatusProvider.class).hasSize(9));
  }

  @Test
  void noActuatorHealthProviderWhenActuatorStatusProviderIsDisabled() {
    runner.withPropertyValues("systemd.status-providers.actuator-health.enabled=false").run(context ->
        assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ActuatorHealthStatusProvider.class).hasSize(9));
  }

  @Test
  void actuatorHealthProviderRequiresActuator() {
    runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class)).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(ActuatorHealthStatusProvider.class);
      assertThat(Systemd.instance().getHealthProviders()).doesNotHaveAnyElementsOfTypes(ActuatorHealthStatusProvider.class).hasSize(9);
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

  @Test
  void cgroupTasksHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.cgroup-tasks.enabled=true", "systemd.status-providers.cgroup-tasks.health=true",
        "systemd.status-providers.cgroup-tasks.threshold=0.75", "systemd.status-providers.cgroup-tasks.unhealthy-delay=30s").run(context -> {
          CgroupTasksStatusProvider provider = context.getBean(CgroupTasksStatusProvider.class);
          assertThat(SystemdProcess.healthEnabled(provider)).isTrue();
          assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.75);
          assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
          assertThat(Systemd.instance().getHealthProviders()).contains(provider);
        });
  }

  @Test
  void cgroupTasksHealthIsOptedIntoWithAThresholdOf90PercentAndNoDelayByDefault() {
    runner.withPropertyValues("systemd.status-providers.cgroup-tasks.enabled=true").run(context -> {
      CgroupTasksStatusProvider provider = context.getBean(CgroupTasksStatusProvider.class);
      assertThat(SystemdProcess.healthEnabled(provider)).isFalse();
      assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.9);
      assertThat(provider.getUnhealthyDelay()).isNull();
    });
  }

  @Test
  void garbageCollectionHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.garbage-collection.enabled=true", "systemd.status-providers.garbage-collection.health=true",
        "systemd.status-providers.garbage-collection.threshold=0.8", "systemd.status-providers.garbage-collection.unhealthy-delay=5m").run(context -> {
          GarbageCollectionStatusProvider provider = context.getBean(GarbageCollectionStatusProvider.class);
          assertThat(SystemdProcess.healthEnabled(provider)).isTrue();
          assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.8);
          assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(5));
          assertThat(Systemd.instance().getHealthProviders()).contains(provider);
        });
  }

  @Test
  void garbageCollectionHealthIsOptedIntoWithAThresholdOf50PercentAndADelayOfOneMinuteByDefault() {
    runner.withPropertyValues("systemd.status-providers.garbage-collection.enabled=true").run(context -> {
      GarbageCollectionStatusProvider provider = context.getBean(GarbageCollectionStatusProvider.class);
      assertThat(SystemdProcess.healthEnabled(provider)).isFalse();
      assertThat(SystemdProcess.threshold(provider)).isEqualTo(0.5);
      assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
    });
  }

  @ParameterizedTest
  @CsvSource({"cgroup-tasks, 0", "cgroup-tasks, 1.5", "garbage-collection, 0", "garbage-collection, 1.5"})
  void failsWithAThresholdOutOfRange(String key, String threshold) {
    runner.withPropertyValues("systemd.status-providers." + key + ".threshold=" + threshold)
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers." + key + ".threshold"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"availability", "file-descriptors", "cgroup-tasks", "garbage-collection", "hikari", "kafka-listeners", "rabbit-listeners",
      "jms-listeners", "r2dbc", "actuator-health"})
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

  @Test
  void r2dbcPoolsAreOnlyReportedOnceOptedInto() {
    runner.run(context -> assertThat(context).doesNotHaveBean(R2dbcPoolStatusProvider.class));
  }

  @Test
  void r2dbcHealthIsDelayedByOneMinuteByDefault() {
    runner.withPropertyValues("systemd.status-providers.r2dbc.enabled=true").run(context -> {
      R2dbcPoolStatusProvider provider = context.getBean(R2dbcPoolStatusProvider.class);
      assertThat(SpringProviders.healthEnabled(provider)).isFalse();
      assertThat(provider.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
    });
  }

  @Test
  void r2dbcHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.r2dbc.enabled=true", "systemd.status-providers.r2dbc.health=true",
        "systemd.status-providers.r2dbc.unhealthy-delay=0").run(context -> {
          R2dbcPoolStatusProvider provider = context.getBean(R2dbcPoolStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(provider)).isTrue();
          assertThat(provider.getUnhealthyDelay()).isZero();
        });
  }

  @Test
  void r2dbcStatusProviderRequiresTheLibrary() {
    runner.withPropertyValues("systemd.status-providers.r2dbc.enabled=true").withClassLoader(new FilteredClassLoader("io.r2dbc.pool.ConnectionPool"))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("systemdR2dbcPoolStatusProvider"));
  }

  @Test
  void listenerHealthIsNeitherSignalledNorDelayedByDefault() {
    runner.run(context -> {
      KafkaListenerStatusProvider kafka = context.getBean(KafkaListenerStatusProvider.class);
      assertThat(SpringProviders.healthEnabled(kafka)).isFalse();
      assertThat(kafka.getUnhealthyDelay()).isNull();
      RabbitListenerStatusProvider rabbit = context.getBean(RabbitListenerStatusProvider.class);
      assertThat(SpringProviders.healthEnabled(rabbit)).isFalse();
      assertThat(rabbit.getUnhealthyDelay()).isNull();
      JmsListenerStatusProvider jms = context.getBean(JmsListenerStatusProvider.class);
      assertThat(SpringProviders.healthEnabled(jms)).isFalse();
      assertThat(jms.getUnhealthyDelay()).isNull();
    });
  }

  @Test
  void listenerHealthIsConfiguredWithThePropertiesOfEachLibrary() {
    runner.withPropertyValues("systemd.status-providers.kafka-listeners.health=true", "systemd.status-providers.kafka-listeners.unhealthy-delay=30s",
        "systemd.status-providers.rabbit-listeners.unhealthy-delay=1m", "systemd.status-providers.jms-listeners.health=true").run(context -> {
          KafkaListenerStatusProvider kafka = context.getBean(KafkaListenerStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(kafka)).isTrue();
          assertThat(kafka.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
          RabbitListenerStatusProvider rabbit = context.getBean(RabbitListenerStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(rabbit)).isFalse();
          assertThat(rabbit.getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
          JmsListenerStatusProvider jms = context.getBean(JmsListenerStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(jms)).isTrue();
          assertThat(jms.getUnhealthyDelay()).isNull();
        });
  }

  @Test
  void rabbitListenerFailuresAreLearnedFromTheEventsOfTheApplicationContext() {
    // one that is stopped
    SimpleMessageListenerContainer container = Mockito.mock(SimpleMessageListenerContainer.class);

    runner.withPropertyValues("systemd.status-providers.rabbit-listeners.health=true")
        .withBean("ordersContainer", MessageListenerContainer.class, () -> container).run(context -> {
          RabbitListenerStatusProvider provider = context.getBean(RabbitListenerStatusProvider.class);
          assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

          // as the container does once it stops by itself
          context.publishEvent(new ListenerContainerConsumerFailedEvent(container, "fatal", new IllegalStateException("fatal"), true));

          assertThat(provider.health().details()).containsExactly(entry("failedListenerContainers", List.of("ordersContainer")));
        });
  }

  @ParameterizedTest
  @CsvSource({
      "org.eclipse.jetty.server.Server, JettyStatusProvider",
      "reactor.netty.http.server.HttpServer, NettyStatusProvider",
      "com.zaxxer.hikari.HikariDataSource, HikariStatusProvider",
      "liquibase.integration.spring.SpringLiquibase, LiquibaseStatusProvider",
      "org.flywaydb.core.Flyway, FlywayStatusProvider",
      "org.springframework.kafka.config.KafkaListenerEndpointRegistry, KafkaListenerStatusProvider",
      "org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry, RabbitListenerStatusProvider",
      "jakarta.jms.Message, JmsListenerStatusProvider",
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

  @Test
  void reportsTheFlywayMigrationsOfSpringBoot() {
    try (MockedStatic<SystemdNotify> notify = Mockito.mockStatic(SystemdNotify.class)) {
      runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
          .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID(), "spring.flyway.locations=classpath:db/flyway")
          .run(context -> {
            assertThat(context).hasNotFailed();
            // the migrations ran while the context started, and were reported as they did
            notify.verify(SystemdProcess.status(() -> Mockito.contains("Flyway: applying migration 1 create users (0 applied)")));
            notify.verify(SystemdProcess.status(() -> Mockito.contains("Flyway: applying migration 3 index orders (2 applied)")));
            assertThat(context.getBean(FlywayStatusProvider.class).status()).isEmpty();
          });
    }
  }

  @Test
  void reportsTheR2dbcConnectionPoolOfSpringBootOnceOptedInto() {
    runner.withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class))
        .withPropertyValues("spring.r2dbc.url=r2dbc:h2:mem:///" + UUID.randomUUID(), "systemd.status-providers.r2dbc.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          // the connection factory of Spring Boot wraps the pool
          assertThat(context.getBean(R2dbcPoolStatusProvider.class).status()).isEqualTo("connectionFactory: 0/10 connections, 0 waiting");
        });
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

  @ParameterizedTest
  @CsvSource({
      "scheduled-tasks, systemdScheduledTaskStatusProvider, ScheduledTaskStatusProvider, TaskExecutorStatusProvider",
      "micrometer, systemdMicrometerStatusProvider, MicrometerStatusProvider, ClassLoaderStatusProvider",
      "version, systemdBuildInfoStatusProvider, BuildInfoStatusProvider, ClassLoaderStatusProvider"
  })
  void scheduledTasksMicrometerAndVersionAreOptedInto(String property, String beanName, String providerClass, String previousProviderClass) {
    runner.run(context -> assertThat(context).doesNotHaveBean(beanName));

    optedIntoStatusProvidersCanBeEnabled(property, beanName, providerClass, previousProviderClass);
  }

  @Test
  void theMetersComeAfterTheOtherStatusProvidersAndTheVersionLast() {
    runner.withPropertyValues("systemd.status-providers.micrometer.enabled=true", "systemd.status-providers.version.enabled=true")
        .run(context -> assertThat(Systemd.instance().getStatusProviders()).extracting(p -> p.getClass().getSimpleName())
            .endsWith("ClassLoaderStatusProvider", "MicrometerStatusProvider", "BuildInfoStatusProvider"));
  }

  @Test
  void reportsTheScheduledMethodsOfTheApplication() {
    runner.withUserConfiguration(ScheduledConfiguration.class).withPropertyValues("systemd.status-providers.scheduled-tasks.enabled=true")
        .run(context -> {
          ScheduledTaskStatusProvider provider = context.getBean(ScheduledTaskStatusProvider.class);
          // both methods ran once the context was refreshed, and one of them failed
          await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 2 (1 failing)"));
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class ScheduledConfiguration {

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    void succeeding() {
      // only its outcome matters
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    void failing() {
      throw new IllegalStateException("failure");
    }
  }

  @Test
  void reportsTheVersionFromTheBuildInformationOfSpringBoot() {
    // as Spring Boot sets them up from META-INF/build-info.properties and git.properties
    Properties build = new Properties();
    build.setProperty("version", "1.4.2");
    Properties git = new Properties();
    git.setProperty("commit.id", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678");

    runner.withPropertyValues("systemd.status-providers.version.enabled=true")
        .withBean(BuildProperties.class, () -> new BuildProperties(build))
        .withBean(GitProperties.class, () -> new GitProperties(git))
        .run(context -> assertThat(context.getBean(BuildInfoStatusProvider.class).status()).isEqualTo("Version: 1.4.2 (a1b2c3d)"));
  }

  @Test
  void reportsNoVersionWithoutBuildInformation() {
    runner.withPropertyValues("systemd.status-providers.version.enabled=true").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(BuildInfoStatusProvider.class).status()).isEmpty();
    });
  }

  @Test
  void webServerHealthIsDelayedByOneMinuteByDefault() {
    runner.run(context -> {
      assertThat(context.getBean(TomcatStatusProvider.class).getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
      assertThat(context.getBean(JettyStatusProvider.class).getUnhealthyDelay()).isEqualTo(Duration.ofMinutes(1));
    });
  }

  @Test
  void webServerHealthIsConfiguredWithItsProperties() {
    runner.withPropertyValues("systemd.status-providers.tomcat.health=true", "systemd.status-providers.tomcat.unhealthy-delay=30s",
        "systemd.status-providers.jetty.health=true", "systemd.status-providers.jetty.unhealthy-delay=0").run(context -> {
          TomcatStatusProvider tomcat = context.getBean(TomcatStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(tomcat)).isTrue();
          assertThat(tomcat.getUnhealthyDelay()).isEqualTo(Duration.ofSeconds(30));
          JettyStatusProvider jetty = context.getBean(JettyStatusProvider.class);
          assertThat(SpringProviders.healthEnabled(jetty)).isTrue();
          assertThat(jetty.getUnhealthyDelay()).isZero();
        });
  }

  @Test
  void theHealthOfEachWebServerIsEnabledOnItsOwn() {
    runner.withPropertyValues("systemd.status-providers.jetty.health=true").run(context -> {
      assertThat(SpringProviders.healthEnabled(context.getBean(TomcatStatusProvider.class))).isFalse();
      assertThat(SpringProviders.healthEnabled(context.getBean(JettyStatusProvider.class))).isTrue();
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"tomcat", "jetty"})
  void failsWithANegativeUnhealthyDelayOfAWebServer(String key) {
    failsWithANegativeUnhealthyDelay(key);
  }

  @ParameterizedTest
  @CsvSource({
      "tomcat, TomcatStatusProvider",
      "jetty, JettyStatusProvider"
  })
  void noWebServerHealthProviderWhenItsStatusProviderIsDisabled(String key, String providerClass) {
    runner.withPropertyValues("systemd.status-providers." + key + ".enabled=false").run(context ->
        assertThat(Systemd.instance().getHealthProviders()).extracting(p -> p.getClass().getSimpleName()).doesNotContain(providerClass).hasSize(9));
  }

  private static final String MICROMETER = "systemd.status-providers.micrometer";

  @Test
  void showsTheConfiguredMetersOfTheMeterRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Gauge.builder("hikaricp.connections.active", () -> 7).tag("pool", "primary").register(registry);
    Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "secondary").register(registry);
    Counter.builder("orders.placed").register(registry).increment(3);

    runner.withBean(MeterRegistry.class, () -> registry)
        .withPropertyValues(MICROMETER + ".enabled=true",
            MICROMETER + ".meters[0].name=hikaricp.connections.active",
            MICROMETER + ".meters[0].tags.pool=primary",
            MICROMETER + ".meters[0].label=Database connections",
            MICROMETER + ".meters[1].name=orders.placed",
            // not registered, so left out
            MICROMETER + ".meters[2].name=orders.failed")
        .run(context -> assertThat(context.getBean(MicrometerStatusProvider.class).status()).isEqualTo("Database connections: 7, orders.placed: 3"));
  }

  @Test
  void findsTheMeterRegistryOfSpringBootAlthoughItsAutoconfigurationsComeAfterThisOne() {
    runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class))
        // the global registry is shared by all the tests
        .withPropertyValues(MICROMETER + ".enabled=true", MICROMETER + ".meters[0].name=orders.placed", "management.metrics.use-global-registry=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          Counter.builder("orders.placed").register(context.getBean(MeterRegistry.class)).increment(3);

          assertThat(context.getBean(MicrometerStatusProvider.class).status()).isEqualTo("orders.placed: 3");
        });
  }

  @Test
  void warnsAndLooksUpTheMetersInTheGlobalRegistryWithoutAMeterRegistry() {
    List<String> warnings = warnings(runner.withPropertyValues(MICROMETER + ".enabled=true", MICROMETER + ".meters[0].name=orders.placed"));

    assertThat(warnings).singleElement().asString().contains("no meter registry").contains("global registry of Micrometer");
  }

  @Test
  void doesNotLookForAMeterRegistryUnlessTheMetersAreEnabled() {
    assertThat(warnings(runner)).isEmpty();
  }

  @Test
  void theMetersRequireMicrometer() {
    runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
        .withPropertyValues(MICROMETER + ".enabled=true", MICROMETER + ".meters[0].name=orders.placed").run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean("systemdMicrometerStatusProvider");
        });
  }

  @Test
  void failsWithAMeterWithoutAName() {
    runner.withPropertyValues(MICROMETER + ".enabled=true", MICROMETER + ".meters[0].name=orders.placed", MICROMETER + ".meters[1].label=Failed orders")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause()
            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
            .hasMessageContaining("systemd.status-providers.micrometer.meters[1].name"));
  }
}
