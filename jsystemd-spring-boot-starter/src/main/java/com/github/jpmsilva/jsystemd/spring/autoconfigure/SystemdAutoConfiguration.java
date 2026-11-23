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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import static java.util.Objects.requireNonNull;

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
import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.VirtualThreadStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ActuatorHealthStatusProvider;
import com.github.jpmsilva.jsystemd.spring.AvailabilityStatusProvider;
import com.github.jpmsilva.jsystemd.spring.BuildInfoStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ConditionalOnSystemd;
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
import com.github.jpmsilva.jsystemd.spring.SystemdLifecycle;
import com.github.jpmsilva.jsystemd.spring.TaskExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TomcatStatusProvider;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.r2dbc.pool.ConnectionPool;
import jakarta.jms.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jetty.server.Server;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import reactor.netty.http.server.HttpServer;

/**
 * Autoconfiguration class for systemd integration.
 *
 * <p>Sets up some basic {@link StatusProvider} as well.
 *
 * @author Joao Silva
 * @author Christian Lorenz
 */
@AutoConfiguration
@ConditionalOnSystemd
@EnableConfigurationProperties(SystemdProperties.class)
public class SystemdAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(SystemdAutoConfiguration.class);

  private final SystemdProperties properties;

  SystemdAutoConfiguration(SystemdProperties properties) {
    this.properties = requireNonNull(properties, "Properties must not be null");
  }

  /**
   * Event listener for the {@link AvailabilityChangeEvent} event to report to systemd that the service is ready.
   *
   * <p>The application is considered ready when the event is a {@link ReadinessState} with the state {@link ReadinessState#ACCEPTING_TRAFFIC}, unless
   * configured to be ready once it has started instead, with <code>systemd.ready-event=application-started</code>, which
   * {@link SystemdSpringApplicationRunListener} reports.
   *
   * @param event the {@link AvailabilityChangeEvent} received
   */
  @EventListener
  public void started(@SuppressWarnings("unused") AvailabilityChangeEvent<ReadinessState> event) {
    if (properties.getReadyEvent() == SystemdProperties.ReadyEvent.ACCEPTING_TRAFFIC && event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
      Systemd.instance().ready();
    }
  }

  @Bean
  SystemdLifecycle systemdLifecycle(ApplicationContext context) {
    return new SystemdLifecycle(context);
  }

  @Bean
  @Order(-10000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "shutdown.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  ShutdownStatusProvider systemdShutdownStatusProvider(ApplicationContext context) {
    return new ShutdownStatusProvider(context);
  }

  @Bean
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "shutdown.enabled", matchIfMissing = true)
  SmartLifecycle systemdShutdownRequestsStage(ShutdownStatusProvider systemdShutdownStatusProvider) {
    return systemdShutdownStatusProvider.requestsStage();
  }

  @Bean
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "shutdown.enabled", matchIfMissing = true)
  SmartLifecycle systemdShutdownBeansStage(ShutdownStatusProvider systemdShutdownStatusProvider) {
    return systemdShutdownStatusProvider.beansStage();
  }

  /**
   * A {@link PhaseStatusProvider} for the application to report what it is doing, sent to systemd right away on every change of phase.
   *
   * @return the phase status provider of the application
   */
  @Bean
  @Order(-9000)
  @ConditionalOnMissingBean(PhaseStatusProvider.class)
  PhaseStatusProvider systemdPhaseStatusProvider() {
    return new PhaseStatusProvider(true);
  }

  @Bean
  @Order(-6000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "heap.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  HeapStatusProvider systemdHeapStatusProvider() {
    return new HeapStatusProvider();
  }

  @Bean
  @Order(-5000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "old-generation.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  OldGenerationStatusProvider systemdOldGenerationStatusProvider() {
    return new OldGenerationStatusProvider();
  }

  @Bean
  @Order(7000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "non-heap.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  NonHeapStatusProvider systemdNonHeapStatusProvider() {
    return new NonHeapStatusProvider();
  }

  @Bean
  @Order(8000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "buffer-pools.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  BufferPoolStatusProvider systemdBufferPoolStatusProvider() {
    return new BufferPoolStatusProvider();
  }

  @Bean
  @Order(6000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "garbage-collection.enabled")
  @ConditionalOnMissingBean
  GarbageCollectionStatusProvider systemdGarbageCollectionStatusProvider(SystemdProperties properties) {
    SystemdProperties.GarbageCollection garbageCollection = properties.getStatusProviders().getGarbageCollection();
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(garbageCollection.isHealth(), garbageCollection.getThreshold());
    provider.setUnhealthyDelay(garbageCollection.getUnhealthyDelay());
    return provider;
  }

  @Bean
  @Order(9000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "class-loader.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  ClassLoaderStatusProvider systemdClassLoaderStatusProvider() {
    return new ClassLoaderStatusProvider();
  }

  @Bean
  @Order(-2000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "threads.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  ThreadStatusProvider systemdThreadStatusProvider(SystemdProperties properties) {
    SystemdProperties.Threads threads = properties.getStatusProviders().getThreads();
    return new ThreadStatusProvider(threads.isDeadlockDetection(), threads.isDeadlockHealth());
  }

  @Bean
  @Order(-1500)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "virtual-threads.enabled")
  @ConditionalOnMissingBean
  VirtualThreadStatusProvider systemdVirtualThreadStatusProvider() {
    return new VirtualThreadStatusProvider();
  }

  @Bean
  @Order(-4000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "cgroup-memory.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  CgroupMemoryStatusProvider systemdCgroupMemoryStatusProvider() {
    return new CgroupMemoryStatusProvider();
  }

  @Bean
  @Order(-3500)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "cgroup-pressure.enabled")
  @ConditionalOnMissingBean
  CgroupPressureStatusProvider systemdCgroupPressureStatusProvider() {
    return new CgroupPressureStatusProvider();
  }

  @Bean
  @Order(-500)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "cgroup-tasks.enabled")
  @ConditionalOnMissingBean
  CgroupTasksStatusProvider systemdCgroupTasksStatusProvider(SystemdProperties properties) {
    SystemdProperties.CgroupTasks cgroupTasks = properties.getStatusProviders().getCgroupTasks();
    CgroupTasksStatusProvider provider = new CgroupTasksStatusProvider(cgroupTasks.isHealth(), cgroupTasks.getThreshold());
    provider.setUnhealthyDelay(cgroupTasks.getUnhealthyDelay());
    return provider;
  }

  @Bean
  @Order(-7000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "availability.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  AvailabilityStatusProvider systemdAvailabilityStatusProvider(SystemdProperties properties) {
    SystemdProperties.Availability availability = properties.getStatusProviders().getAvailability();
    AvailabilityStatusProvider provider = new AvailabilityStatusProvider(availability.isHealth());
    provider.setUnhealthyDelay(availability.getUnhealthyDelay());
    return provider;
  }

  @Bean
  @Order(4000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "task-executors.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean
  TaskExecutorStatusProvider systemdTaskExecutorStatusProvider(ConfigurableListableBeanFactory beanFactory) {
    return new TaskExecutorStatusProvider(beanFactory);
  }

  @Bean
  @Order(4200)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "scheduled-tasks.enabled")
  @ConditionalOnMissingBean
  ScheduledTaskStatusProvider systemdScheduledTaskStatusProvider(ConfigurableListableBeanFactory beanFactory) {
    return new ScheduledTaskStatusProvider(beanFactory);
  }

  /**
   * The status provider of the version of the application, from the build information that Spring Boot exposes, when the application has it: without
   * it, nothing is shown.
   *
   * @param build the build information of the application, if any
   * @param git the information of the commit the application was built from, if any
   * @return the status provider of the version of the application
   */
  @Bean
  @Order(11000)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "version.enabled")
  @ConditionalOnMissingBean
  BuildInfoStatusProvider systemdBuildInfoStatusProvider(ObjectProvider<BuildProperties> build, ObjectProvider<GitProperties> git) {
    return new BuildInfoStatusProvider(build.getIfUnique(), git.getIfUnique());
  }

  /**
   * Configuration class for the status providers that read the operating system beans specific to the JVMs based on OpenJDK, which are left out on
   * other JVMs.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  public static class SystemdOperatingSystemConfiguration {

    SystemdOperatingSystemConfiguration() {
    }

    @Bean
    @Order(5000)
    @ConditionalOnClass(name = "com.sun.management.OperatingSystemMXBean")
    @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "cpu.enabled")
    @ConditionalOnMissingBean
    CpuStatusProvider systemdCpuStatusProvider() {
      return new CpuStatusProvider();
    }

    @Bean
    @Order(-1000)
    @ConditionalOnClass(name = "com.sun.management.UnixOperatingSystemMXBean")
    @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "file-descriptors.enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    FileDescriptorStatusProvider systemdFileDescriptorStatusProvider(SystemdProperties properties) {
      SystemdProperties.FileDescriptors fileDescriptors = properties.getStatusProviders().getFileDescriptors();
      FileDescriptorStatusProvider provider = new FileDescriptorStatusProvider(fileDescriptors.isHealth(), fileDescriptors.getThreshold());
      provider.setUnhealthyDelay(fileDescriptors.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when running under the embedded Tomcat of Spring Boot.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({Tomcat.class, TomcatWebServer.class})
  public static class SystemdTomcatConfiguration {

    SystemdTomcatConfiguration() {
    }

    @Bean
    @Order(1000)
    @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "tomcat.enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    TomcatStatusProvider systemdTomcatStatusProvider(SystemdProperties properties) {
      SystemdProperties.WebServer tomcat = properties.getStatusProviders().getTomcat();
      TomcatStatusProvider provider = new TomcatStatusProvider(tomcat.isHealth());
      provider.setUnhealthyDelay(tomcat.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when running under the embedded Jetty of Spring Boot.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({Server.class, JettyWebServer.class})
  public static class SystemdJettyConfiguration {

    SystemdJettyConfiguration() {
    }

    @Bean
    @Order(2000)
    @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "jetty.enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    JettyStatusProvider systemdJettyStatusProvider(SystemdProperties properties) {
      SystemdProperties.WebServer jetty = properties.getStatusProviders().getJetty();
      JettyStatusProvider provider = new JettyStatusProvider(jetty.isHealth());
      provider.setUnhealthyDelay(jetty.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using Micrometer, showing the values of chosen meters in the status.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "micrometer.enabled")
  public static class SystemdMicrometerConfiguration {

    SystemdMicrometerConfiguration() {
    }

    /**
     * The status provider of the meters, which finds them in the meter registry of the application: the primary one when there are several, as Spring Boot
     * sets up, which measures with all the others. The registry is looked up when the bean is created, rather than required for it to be, so that the
     * registry is found whatever the order of the autoconfigurations.
     *
     * @param registryProvider the meter registry of the application, if any
     * @param properties the properties of the integration
     * @return the status provider of the meters
     */
    @Bean
    @Order(10000)
    @ConditionalOnMissingBean
    MicrometerStatusProvider systemdMicrometerStatusProvider(ObjectProvider<MeterRegistry> registryProvider, SystemdProperties properties) {
      MeterRegistry registry = registryProvider.getIfUnique();
      if (registry == null) {
        logger.warn("There is no meter registry, or there are several and none is the primary one, so the meters are looked up in the global registry of "
            + "Micrometer; check that the metrics autoconfiguration of Spring Boot is not excluded");
        registry = Metrics.globalRegistry;
      }
      List<MicrometerStatusProvider.ShownMeter> meters = properties.getStatusProviders().getMicrometer().getMeters().stream()
          .map(meter -> new MicrometerStatusProvider.ShownMeter(requireNonNull(meter.getName(), "Name must not be null"), meter.getTags(), meter.getLabel()))
          .toList();
      return new MicrometerStatusProvider(registry, meters);
    }
  }

  /**
   * Configuration class for systemd integration when running under the embedded Reactor Netty of Spring Boot.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({HttpServer.class, NettyServerCustomizer.class})
  public static class SystemdNettyConfiguration {

    SystemdNettyConfiguration() {
    }

    @Bean
    @Order(3000)
    @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "netty.enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    NettyStatusProvider systemdNettyStatusProvider() {
      return new NettyStatusProvider();
    }
  }

  /**
   * Configuration class for systemd integration when using HikariCP connection pools.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({HikariDataSource.class, DataSourceUnwrapper.class})
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "hikari.enabled", matchIfMissing = true)
  public static class SystemdHikariConfiguration {

    SystemdHikariConfiguration() {
    }

    @Bean
    @Order(-3000)
    @ConditionalOnMissingBean
    HikariStatusProvider systemdHikariStatusProvider(ConfigurableListableBeanFactory beanFactory,
        SystemdProperties properties) {
      SystemdProperties.Hikari hikari = properties.getStatusProviders().getHikari();
      HikariStatusProvider provider = new HikariStatusProvider(beanFactory, hikari.isHealth());
      provider.setUnhealthyDelay(hikari.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using Liquibase, reporting the progress of the database migrations run on startup.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(SpringLiquibase.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "liquibase.enabled", matchIfMissing = true)
  public static class SystemdLiquibaseConfiguration {

    SystemdLiquibaseConfiguration() {
    }

    /**
     * The status provider of the Liquibase migrations, which is also a bean post processor, so it is created before the other beans: it is added to the
     * status providers of systemd right away, rather than once all beans are created, so that the migrations are reported while they run on startup.
     *
     * @return the status provider of the Liquibase migrations
     */
    @Bean
    static LiquibaseStatusProvider systemdLiquibaseStatusProvider() {
      LiquibaseStatusProvider provider = new LiquibaseStatusProvider();
      Systemd.instance().addStatusProviders(provider);
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using Flyway, reporting the progress of the database migrations run on startup.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({Flyway.class, FlywayConfigurationCustomizer.class})
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "flyway.enabled", matchIfMissing = true)
  public static class SystemdFlywayConfiguration {

    SystemdFlywayConfiguration() {
    }

    /**
     * The status provider of the Flyway migrations, which is also a customizer of the configuration of Flyway, so it is created before Flyway is, and
     * runs the migrations: it is added to the status providers of systemd right away, rather than once all beans are created, so that the migrations are
     * reported while they run on startup.
     *
     * @return the status provider of the Flyway migrations
     */
    @Bean
    FlywayStatusProvider systemdFlywayStatusProvider() {
      FlywayStatusProvider provider = new FlywayStatusProvider();
      Systemd.instance().addStatusProviders(provider);
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using the Kafka listener containers of Spring for Apache Kafka.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(KafkaListenerEndpointRegistry.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "kafka-listeners.enabled", matchIfMissing = true)
  public static class SystemdKafkaListenerConfiguration {

    SystemdKafkaListenerConfiguration() {
    }

    @Bean
    @Order(4400)
    @ConditionalOnMissingBean
    KafkaListenerStatusProvider systemdKafkaListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, SystemdProperties properties) {
      SystemdProperties.Listeners listeners = properties.getStatusProviders().getKafkaListeners();
      KafkaListenerStatusProvider provider = new KafkaListenerStatusProvider(beanFactory, listeners.isHealth());
      provider.setUnhealthyDelay(listeners.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using the RabbitMQ listener containers of Spring AMQP.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(RabbitListenerEndpointRegistry.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "rabbit-listeners.enabled", matchIfMissing = true)
  public static class SystemdRabbitListenerConfiguration {

    SystemdRabbitListenerConfiguration() {
    }

    @Bean
    @Order(4600)
    @ConditionalOnMissingBean
    RabbitListenerStatusProvider systemdRabbitListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, SystemdProperties properties) {
      SystemdProperties.Listeners listeners = properties.getStatusProviders().getRabbitListeners();
      RabbitListenerStatusProvider provider = new RabbitListenerStatusProvider(beanFactory, listeners.isHealth());
      provider.setUnhealthyDelay(listeners.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using the JMS listener containers of Spring.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass({Message.class, JmsListenerEndpointRegistry.class})
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "jms-listeners.enabled", matchIfMissing = true)
  public static class SystemdJmsListenerConfiguration {

    SystemdJmsListenerConfiguration() {
    }

    @Bean
    @Order(4800)
    @ConditionalOnMissingBean
    JmsListenerStatusProvider systemdJmsListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, SystemdProperties properties) {
      SystemdProperties.Listeners listeners = properties.getStatusProviders().getJmsListeners();
      JmsListenerStatusProvider provider = new JmsListenerStatusProvider(beanFactory, listeners.isHealth());
      provider.setUnhealthyDelay(listeners.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using R2DBC connection pools, once opted into.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(ConnectionPool.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "r2dbc.enabled")
  public static class SystemdR2dbcConfiguration {

    SystemdR2dbcConfiguration() {
    }

    @Bean
    @Order(-2500)
    @ConditionalOnMissingBean
    R2dbcPoolStatusProvider systemdR2dbcPoolStatusProvider(ConfigurableListableBeanFactory beanFactory, SystemdProperties properties) {
      SystemdProperties.R2dbc r2dbc = properties.getStatusProviders().getR2dbc();
      R2dbcPoolStatusProvider provider = new R2dbcPoolStatusProvider(beanFactory, r2dbc.isHealth());
      provider.setUnhealthyDelay(r2dbc.getUnhealthyDelay());
      return provider;
    }
  }

  /**
   * Configuration class for systemd integration when using Spring Boot Actuator, providing a {@link HealthProvider} based on its health contributors.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnSystemd
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(prefix = SystemdProperties.STATUS_PROVIDERS_PREFIX, name = "actuator-health.enabled", matchIfMissing = true)
  public static class SystemdActuatorHealthConfiguration {

    SystemdActuatorHealthConfiguration() {
    }

    @Bean
    @Order(-8000)
    @ConditionalOnMissingBean(ActuatorHealthStatusProvider.class)
    ActuatorHealthStatusProvider systemdActuatorHealthStatusProvider(ObjectProvider<HealthContributorRegistry> registryProvider,
        ObjectProvider<ReactiveHealthContributorRegistry> reactiveRegistryProvider, ObjectProvider<HealthEndpointGroups> groupsProvider,
        SystemdProperties systemdProperties) {
      requireNonNull(registryProvider, "Registry provider must not be null");
      requireNonNull(reactiveRegistryProvider, "Reactive registry provider must not be null");
      requireNonNull(systemdProperties, "Properties must not be null");
      SystemdProperties.ActuatorHealth properties = systemdProperties.getStatusProviders().getActuatorHealth();

      // the same contributors, with the same names, as the health endpoint, which also adapts the reactive ones
      List<HealthContributors> contributors = new ArrayList<>();
      HealthContributorRegistry registry = registryProvider.getIfAvailable();
      if (registry == null) {
        logger.warn("There is no health contributor registry, so the Spring Boot Actuator health is always reported as healthy; check that the health "
            + "contributor registry autoconfiguration of Spring Boot is not excluded");
      } else {
        contributors.add(registry);
      }
      reactiveRegistryProvider.ifAvailable(reactiveRegistry -> contributors.add(reactiveRegistry.asHealthContributors()));
      Set<Status> unhealthyStatusCodes = properties.getUnhealthyStatusCodes().stream().map(Status::new).collect(Collectors.toSet());
      if (properties.isHealth() && unhealthyStatusCodes.isEmpty() && Systemd.watchdogTimeout().isPresent() && !systemdProperties.isWatchdogSuppressWarning()) {
        logger.warn("The systemd watchdog is enabled but no health status codes are considered as unhealthy, so the watchdog heartbeat will never be "
            + "suppressed; set '{}.unhealthy-status-codes' with the status codes to consider as unhealthy, or suppress this warning with "
            + "'{}.watchdog-suppress-warning=true'", SystemdProperties.ActuatorHealth.PREFIX, SystemdProperties.PREFIX);
      }
      ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(HealthContributors.of(contributors.toArray(HealthContributors[]::new)),
          unhealthyStatusCodes, member(properties.getGroup(), groupsProvider), properties.isHealth());
      provider.setUnhealthyDelay(properties.getUnhealthyDelay());
      return provider;
    }

    /**
     * Returns the membership test of the health group with the given name, the one of the health endpoint, or one that takes all the health indicators into
     * account without a group.
     *
     * @throws InvalidConfigurationPropertyValueException if there is no health group with the given name
     */
    private static Predicate<String> member(@Nullable String group, ObjectProvider<HealthEndpointGroups> groupsProvider) {
      if (group == null) {
        return name -> true;
      }
      String property = SystemdProperties.ActuatorHealth.PREFIX + ".group";
      HealthEndpointGroups groups = groupsProvider.getIfAvailable();
      if (groups == null) {
        throw new InvalidConfigurationPropertyValueException(property, group, "There are no health groups, as the health endpoint of Spring Boot "
            + "Actuator is not available: check that it is not disabled, nor its autoconfiguration excluded");
      }
      HealthEndpointGroup healthGroup = groups.get(group);
      if (healthGroup == null) {
        throw new InvalidConfigurationPropertyValueException(property, group, "There is no such health group; the health groups are " + groups.getNames()
            + " (the liveness and readiness groups are not set up with management.endpoint.health.probes.enabled=false)");
      }
      return healthGroup::isMember;
    }
  }

  /**
   * Makes the systemd status include the status of the application, once all beans are created, so that the {@link StatusProvider} beans defined by
   * the application are also found. The beans are added to the status providers of systemd, after the ones already registered (such as the startup status
   * providers), in the order given by the <code>@Order</code> of their bean methods or classes. Beans already registered are not added again. While there
   * is no status provider, no status is sent.
   *
   * <p>The built-in status providers, such as the {@link HeapStatusProvider}, are added while enabled, with the
   * <code>systemd.status-providers.&lt;key&gt;.enabled</code> properties: most of them are enabled by default, and the others, such as the
   * {@link GarbageCollectionStatusProvider} and the {@link CpuStatusProvider}, are opted into.
   *
   * @param statuses the status providers of the application
   * @return the callback that adds the status providers to the systemd status
   */
  @Bean
  SmartInitializingSingleton systemdStatusProviders(ObjectProvider<StatusProvider> statuses) {
    return () -> {
      Systemd systemd = Systemd.instance();
      List<StatusProvider> registered = systemd.getStatusProviders();
      systemd.addStatusProviders(statuses.orderedStream()
          .filter(provider -> !registered.contains(provider))
          .toList());
    };
  }

  /**
   * Makes the systemd watchdog take the application health into account, once all beans are created, so that the {@link HealthProvider} beans defined by
   * the application are also found. The beans are added to the health providers of systemd, after the ones already registered, in the order given by the
   * <code>@Order</code> of their bean methods or classes. Beans already registered are not added again. While there is no health provider, the watchdog
   * heartbeat is always sent.
   *
   * <p>The built-in status providers that are also health providers, such as the {@link ThreadStatusProvider}, the {@link FileDescriptorStatusProvider},
   * the {@link CgroupTasksStatusProvider}, the {@link GarbageCollectionStatusProvider}, the {@link HikariStatusProvider}, the
   * {@link TomcatStatusProvider}, the {@link JettyStatusProvider}, the {@link AvailabilityStatusProvider} and the
   * {@link ActuatorHealthStatusProvider}, are added while enabled. Their health conditions are controlled by their own properties, all disabled by
   * default, so that they are always healthy unless opted into.
   *
   * @param healthProviders the health providers of the application
   * @return the callback that adds the health providers to the watchdog
   */
  @Bean
  SmartInitializingSingleton systemdHealthProviders(ObjectProvider<HealthProvider> healthProviders) {
    return () -> {
      Systemd systemd = Systemd.instance();
      List<HealthProvider> registered = systemd.getHealthProviders();
      systemd.addHealthProviders(healthProviders.orderedStream()
          .filter(provider -> !registered.contains(provider))
          .toList());
    };
  }
}
