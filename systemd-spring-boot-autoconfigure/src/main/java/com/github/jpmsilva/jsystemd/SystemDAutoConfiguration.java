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

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnSystemD
public class SystemDAutoConfiguration {

  private static final Predicate<Object> IS_NULL = Objects::isNull;
  private static final Predicate<Object> IS_NOT_NULL = IS_NULL.negate();
  private static final Predicate<String> IS_EMPTY = String::isEmpty;
  private static final Predicate<String> IS_NOT_EMPTY = IS_EMPTY.negate();

  @Bean
  SystemDNotify systemDNotify() {
    return SystemDUtilities.getSystemDNotify();
  }

  @Bean
  ApplicationListener<ApplicationReadyEvent> systemDApplicationListener(SystemDNotify systemDNotify) {
    return new SystemDApplicationListener(systemDNotify);
  }

  @Bean
  SystemDNotifyStatus systemDNotifyHeapStatus() {
    return new SystemDNotifyHeapStatus();
  }

  @Bean
  SystemDNotifyStatus systemDNotifyNonHeapStatus() {
    return new SystemDNotifyNonHeapStatus();
  }

  @Bean
  SystemDNotifyStatus systemDNotifyClassLoaderStatus() {
    return new SystemDNotifyClassLoaderStatus();
  }

  @Configuration
  static class SystemDAutoStatusConfiguration {

    private final SystemDNotify systemDNotify;
    private final List<SystemDNotifyStatus> statuses;

    @Autowired
    SystemDAutoStatusConfiguration(SystemDNotify systemDNotify, ObjectProvider<List<SystemDNotifyStatus>> statuses) {
      this.systemDNotify = systemDNotify;
      this.statuses = Optional.ofNullable(statuses.getIfAvailable())
          .map(t -> t.stream().sorted(new AnnotationAwareOrderComparator()).collect(Collectors.toList()))
          .orElse(new ArrayList<>());
    }

    @Scheduled(fixedDelay = 10000)
    void updateStatus() {
      systemDNotify.status(join(statuses.stream()
          .map(SystemDNotifyStatus::status)
          .filter(IS_NOT_NULL)
          .filter(IS_NOT_EMPTY)
          .collect(Collectors.toList())
          .listIterator()));
    }

    private String join(ListIterator<String> statusMessages) {
      StringBuilder builder = new StringBuilder();
      if (statusMessages.hasNext()) {
        builder.append(statusMessages.next());
        while (statusMessages.hasNext()) {
          builder.append(", ");
          builder.append(statusMessages.next());
        }
      }
      return builder.toString();
    }

  }

  private static class SystemDApplicationListener implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private final SystemDNotify systemDNotify;

    private SystemDApplicationListener(SystemDNotify systemDNotify) {
      this.systemDNotify = systemDNotify;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
      systemDNotify.signalReady();
    }

    @Override
    public int getOrder() {
      return LOWEST_PRECEDENCE;
    }

  }

}

