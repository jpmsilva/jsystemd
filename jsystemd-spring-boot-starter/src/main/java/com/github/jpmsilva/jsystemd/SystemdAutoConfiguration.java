/*
 * Copyright 2018 Joao Silva
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

import com.github.jpmsilva.groundlevel.utilities.QuackAnnotationAwareOrderComparator;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.catalina.startup.Tomcat;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * Auto-configuration class for systemd integration.
 *
 * <p>Sets up some basic {@link SystemdNotifyStatusProvider} as well.
 *
 * @author Joao Silva
 * @author Christian Lorenz
 */
@Configuration
@ConditionalOnSystemd
public class SystemdAutoConfiguration {

  @NotNull
  private final Systemd systemd;

  @Autowired
  public SystemdAutoConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd) {
    this.systemd = Objects.requireNonNull(systemd, "Systemd must not be null");
  }

  @EventListener
  public void started(@SuppressWarnings("unused") ApplicationReadyEvent event) {
    systemd.ready();
  }

  @Bean
  @NotNull
  @Order(-3000)
  SystemdNotifyStatusProvider systemdNotifyHeapStatus() {
    return new SystemdNotifyHeapStatusProvider();
  }

  @Bean
  @NotNull
  @Order(-2000)
  SystemdNotifyStatusProvider systemdNotifyNonHeapStatus() {
    return new SystemdNotifyNonHeapStatusProvider();
  }

  @Bean
  @NotNull
  @Order(-1000)
  SystemdNotifyStatusProvider systemdNotifyClassLoaderStatus() {
    return new SystemdNotifyClassLoaderStatusProvider();
  }

  @Configuration
  static class SystemdStatusProviderConfiguration {

    @Autowired
    SystemdStatusProviderConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd,
        @NotNull ConfigurableApplicationContext applicationContext, @NotNull ObjectProvider<List<SystemdNotifyStatusProvider>> statuses) {
      Objects.requireNonNull(systemd, "Systemd must not be null");
      Objects.requireNonNull(applicationContext, "Application context must not be null");
      Objects.requireNonNull(statuses, "Statuses must not be null");

      Set<SystemdNotifyStatusProvider> uniqueProviders = new HashSet<>();
      uniqueProviders.addAll(Optional.ofNullable(statuses.getIfAvailable()).orElse(emptyList()));
      uniqueProviders.addAll(systemd.getStatusProviders());

      List<SystemdNotifyStatusProvider> newProviders = new ArrayList<>(uniqueProviders);
      newProviders.sort(new QuackAnnotationAwareOrderComparator(applicationContext.getBeanFactory()));
      systemd.setStatusProviders(newProviders);
    }
  }

  /**
   * Auto-configuration class for systemd integration when running under Tomcat.
   */
  @Configuration
  @ConditionalOnClass(Tomcat.class)
  public static class SystemdAutoTomcatConfiguration {

    @Bean
    @NotNull
    SystemdNotifyTomcatStatusProvider systemdNotifyTomcatStatusProvider() {
      return new SystemdNotifyTomcatStatusProvider();
    }
  }

  /**
   * Auto-configuration class for systemd integration when using Spring Boot Actuator.
   */
  @Configuration
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(prefix = "systemd.health-provider.enabled")
  @EnableConfigurationProperties(SystemdHealthProviderProperties.class)
  public static class SystemdAutoActuatorHealthConfiguration {

    @Bean
    @NotNull
    SystemdNotifyActuatorHealthProvider systemdNotifyActuatorHealthProvider(
        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") @NotNull Systemd systemd,
        @NotNull ObjectProvider<List<HealthIndicator>> healthIndicatorsProvider, @NotNull SystemdHealthProviderProperties properties) {
      Objects.requireNonNull(systemd, "Systemd must not be null");
      Objects.requireNonNull(healthIndicatorsProvider, "Health indicators provider must not be null");
      Objects.requireNonNull(properties, "Properties must not be null");

      List<HealthIndicator> healthIndicators = Optional.ofNullable(healthIndicatorsProvider.getIfAvailable()).orElse(emptyList());
      Set<Status> unhealthyStatusCodes = properties.getUnhealthyStatusCodes().stream().map(Status::new).collect(Collectors.toSet());
      SystemdNotifyActuatorHealthProvider healthProvider = new SystemdNotifyActuatorHealthProvider(healthIndicators, unhealthyStatusCodes);
      if (properties.getUnhealthyPendingPeriodMs() != null) {
        systemd.setHealthProvider(new PendingHealthProvider(healthProvider, properties.getUnhealthyPendingPeriodMs(), ChronoUnit.MILLIS));
      } else {
        systemd.setHealthProvider(healthProvider);
      }
      return healthProvider;
    }
  }
}
