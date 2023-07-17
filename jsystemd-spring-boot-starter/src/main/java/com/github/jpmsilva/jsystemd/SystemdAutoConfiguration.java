/*
 * Copyright 2018-2023 Joao Silva
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

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.catalina.startup.Tomcat;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * Autoconfiguration class for systemd integration.
 *
 * <p>Sets up some basic {@link SystemdStatusProvider} as well.
 *
 * @author Joao Silva
 * @author Christian Lorenz
 */
@AutoConfiguration
@ConditionalOnSystemd
public class SystemdAutoConfiguration {

  @NotNull
  private final Systemd systemd;

  @Autowired
  SystemdAutoConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd) {
    this.systemd = requireNonNull(systemd, "Systemd must not be null");
  }

  /**
   * Event listener for the {@link AvailabilityChangeEvent} event to report to systemd that the service is ready.
   *
   * <p>The application is considered ready when the event is a {@link ReadinessState} with the state {@link ReadinessState#ACCEPTING_TRAFFIC}.
   *
   * @param event the {@link AvailabilityChangeEvent} received
   */
  @EventListener
  public void started(@SuppressWarnings("unused") AvailabilityChangeEvent<ReadinessState> event) {
    if(event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
      systemd.ready();
    }
  }

  @Bean
  @NotNull
  SystemdLifecycle systemdLifecycle() {
    return new SystemdLifecycle(systemd);
  }

  @Bean
  @NotNull
  SystemdServletContextListener systemdServletContextListener() {
    return new SystemdServletContextListener();
  }

  @Bean
  @NotNull
  SystemdStatusProvider systemdNotifyHeapStatus() {
    return new SystemdHeapStatusProvider();
  }

  @Bean
  @NotNull
  SystemdStatusProvider systemdNotifyNonHeapStatus() {
    return new SystemdNonHeapStatusProvider();
  }

  @Bean
  @NotNull
  SystemdStatusProvider systemdNotifyClassLoaderStatus() {
    return new SystemdClassLoaderStatusProvider();
  }

  @Configuration
  @ConditionalOnSystemd
  static class SystemdStatusProviderConfiguration {

    @Autowired
    SystemdStatusProviderConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd,
        @NotNull ConfigurableApplicationContext applicationContext, @NotNull ObjectProvider<List<SystemdStatusProvider>> statuses) {
      requireNonNull(systemd, "Systemd must not be null");
      requireNonNull(applicationContext, "Application context must not be null");
      requireNonNull(statuses, "Statuses must not be null");

      Set<SystemdStatusProvider> uniqueProviders = new HashSet<>();
      uniqueProviders.addAll(Optional.ofNullable(statuses.getIfAvailable()).orElse(emptyList()));
      uniqueProviders.addAll(systemd.getStatusProviders());

      List<SystemdStatusProvider> newProviders = new ArrayList<>(uniqueProviders);
      newProviders.sort(AnnotationAwareOrderComparator.INSTANCE);
      systemd.setStatusProviders(newProviders);
    }
  }

  /**
   * Autoconfiguration class for systemd integration when running under Tomcat.
   */
  @Configuration
  @ConditionalOnSystemd
  @ConditionalOnClass(Tomcat.class)
  public static class SystemdAutoTomcatConfiguration {

    SystemdAutoTomcatConfiguration() {
    }

    @Bean
    @NotNull
    SystemdTomcatStatusProvider systemdNotifyTomcatStatusProvider() {
      return new SystemdTomcatStatusProvider();
    }
  }

  /**
   * Autoconfiguration class for systemd integration when using Spring Boot Actuator.
   */
  @Configuration
  @ConditionalOnSystemd
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(name = "enabled", prefix = "systemd.health-provider")
  @EnableConfigurationProperties(SystemdHealthProviderProperties.class)
  public static class SystemdAutoActuatorHealthConfiguration {

    SystemdAutoActuatorHealthConfiguration() {
    }

    @Bean
    @NotNull
    SystemdActuatorHealthProvider systemdNotifyActuatorHealthProvider(
        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd,
        @NotNull ObjectProvider<List<HealthIndicator>> healthIndicatorsProvider, @NotNull SystemdHealthProviderProperties properties) {
      requireNonNull(systemd, "Systemd must not be null");
      requireNonNull(healthIndicatorsProvider, "Health indicators provider must not be null");
      requireNonNull(properties, "Properties must not be null");

      List<HealthIndicator> healthIndicators = Optional.ofNullable(healthIndicatorsProvider.getIfAvailable()).orElse(emptyList());
      Set<Status> unhealthyStatusCodes = properties.getUnhealthyStatusCodes().stream().map(Status::new).collect(Collectors.toSet());
      SystemdActuatorHealthProvider healthProvider = new SystemdActuatorHealthProvider(healthIndicators, unhealthyStatusCodes);
      if (properties.getUnhealthyPendingPeriodMs() != null) {
        systemd.setHealthProvider(new PendingHealthProvider(healthProvider, properties.getUnhealthyPendingPeriodMs(), ChronoUnit.MILLIS));
      } else {
        systemd.setHealthProvider(healthProvider);
      }
      return healthProvider;
    }
  }
}
