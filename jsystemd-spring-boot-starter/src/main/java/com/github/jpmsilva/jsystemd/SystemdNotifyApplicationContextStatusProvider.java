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

import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdNotifyStatusProvider} that provides information regarding the bean creation progress of a bean factory.
 *
 * @author Joao Silva
 * @see BeanPostProcessor
 */
@Order(-4000)
public class SystemdNotifyApplicationContextStatusProvider implements SystemdNotifyStatusProvider, BeanPostProcessor {

  private final Systemd systemd;
  private final int applicationId;
  private final String contextId;
  private final ConfigurableListableBeanFactory factory;
  private Set<String> definitions;
  private String status = "";

  /**
   * Creates a new instance using the provided {@link Systemd} as the integration point.
   *
   * <p>This constructor automatically registers the created instance as a status provider in the {@link Systemd} passed as a parameter.
   *
   * @param systemd the {@link Systemd} to send status information to
   */
  SystemdNotifyApplicationContextStatusProvider(@SuppressWarnings("SameParameterValue") Systemd systemd, int applicationId, String contextId,
      ConfigurableListableBeanFactory factory) {
    this.systemd = systemd;
    this.applicationId = applicationId;
    this.contextId = contextId;
    this.factory = factory;
    this.systemd.addStatusProviders(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String status() {
    return systemd.isReady() ? "" : status;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName)
      throws BeansException {
    if (factory != null) {
      ensureDefinitionsLoaded();
      status = String.format("Application %d (%s): creating bean %d of %d", applicationId, contextId, getSingletonCount(), definitions.size());
      systemd.extendTimeout();
      systemd.updateStatus();
    }
    return bean;
  }

  private void ensureDefinitionsLoaded() {
    if (definitions == null) {
      definitions = Arrays.stream(factory.getBeanDefinitionNames())
          .filter(isSingleton(factory))
          .collect(Collectors.toSet());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }

  private long getSingletonCount() {
    return definitions.stream()
        .filter(factory::containsSingleton)
        .count();
  }

  private Predicate<? super String> isSingleton(ConfigurableListableBeanFactory factory) {
    return beanName -> factory.getBeanDefinition(beanName).isSingleton();
  }
}
