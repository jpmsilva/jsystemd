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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

@Configuration
@ConditionalOnSystemd
public class SystemdAutoConfiguration {

  @Bean
  @Autowired
  ApplicationListener<ApplicationReadyEvent> systemdApplicationReadyListener(Systemd systemd) {
    return event -> systemd.ready();
  }

  @Bean
  SystemdNotifyStatusProvider systemdNotifyHeapStatus() {
    return new SystemdNotifyHeapStatusProvider();
  }

  @Bean
  SystemdNotifyStatusProvider systemdNotifyNonHeapStatus() {
    return new SystemdNotifyNonHeapStatusProvider();
  }

  @Bean
  SystemdNotifyStatusProvider systemdNotifyClassLoaderStatus() {
    return new SystemdNotifyClassLoaderStatusProvider();
  }

  @Configuration
  static class SystemdStatusProviderConfiguration {

    @Autowired
    SystemdStatusProviderConfiguration(Systemd systemd,
        ObjectProvider<List<SystemdNotifyStatusProvider>> statuses) {
      Set<SystemdNotifyStatusProvider> newProviders = new TreeSet<>(getSourceProviderComparator());
      newProviders.addAll(Optional.ofNullable(statuses.getIfAvailable()).orElse(emptyList()));
      newProviders.addAll(systemd.getStatusProviders());
      systemd.setStatusProviders(new ArrayList<>(newProviders));
    }

    private Comparator<Object> getSourceProviderComparator() {
      return (new AnnotationAwareOrderComparator()).withSourceProvider(obj -> {
        if (obj instanceof SystemdNotifyHeapStatusProvider) {
          return (Ordered) () -> -3000;
        }
        if (obj instanceof SystemdNotifyNonHeapStatusProvider) {
          return (Ordered) () -> -2000;
        }
        if (obj instanceof SystemdNotifyClassLoaderStatusProvider) {
          return (Ordered) () -> -1000;
        }
        return obj;
      });
    }

  }

  @Configuration
  @ConditionalOnClass(Tomcat.class)
  public static class SystemdAutoTomcatConfiguration {

    @Bean
    SystemdNotifyTomcatStatusProvider systemdNotifyTomcatStatusProvider() {
      return new SystemdNotifyTomcatStatusProvider();
    }

  }

}

