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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
 */
@Configuration
@ConditionalOnSystemd
public class SystemdAutoConfiguration {

  private final Systemd systemd;

  @Autowired
  public SystemdAutoConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") Systemd systemd) {
    this.systemd = systemd;
  }

  @EventListener
  public void started(@SuppressWarnings("unused") ApplicationReadyEvent event) {
    systemd.ready();
  }

  @Bean
  @Order(-3000)
  SystemdNotifyStatusProvider systemdNotifyHeapStatus() {
    return new SystemdNotifyHeapStatusProvider();
  }

  @Bean
  @Order(-2000)
  SystemdNotifyStatusProvider systemdNotifyNonHeapStatus() {
    return new SystemdNotifyNonHeapStatusProvider();
  }

  @Bean
  @Order(-1000)
  SystemdNotifyStatusProvider systemdNotifyClassLoaderStatus() {
    return new SystemdNotifyClassLoaderStatusProvider();
  }

  @Configuration
  static class SystemdStatusProviderConfiguration {

    @Autowired
    SystemdStatusProviderConfiguration(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") Systemd systemd,
        ConfigurableApplicationContext applicationContext, ObjectProvider<List<SystemdNotifyStatusProvider>> statuses) {
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
    SystemdNotifyTomcatStatusProvider systemdNotifyTomcatStatusProvider() {
      return new SystemdNotifyTomcatStatusProvider();
    }
  }
}
