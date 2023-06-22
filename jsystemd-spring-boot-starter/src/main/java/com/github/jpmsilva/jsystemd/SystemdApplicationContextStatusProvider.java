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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdStatusProvider} that provides information regarding the bean creation progress of a bean factory.
 *
 * @author Joao Silva
 * @see BeanPostProcessor
 */
@Order(-4000)
public class SystemdApplicationContextStatusProvider implements SystemdStatusProvider, BeanPostProcessor {

  @NotNull
  private final Systemd systemd;
  private final int applicationId;
  private final String contextId;
  private final ConfigurableListableBeanFactory factory;
  private boolean definitionsLoaded = false;
  @NotNull
  private Set<String> definitions = new HashSet<>();
  @NotNull
  private String status = "";

  /**
   * Creates a new instance using the provided {@link Systemd} as the integration point.
   *
   * <p>This constructor automatically registers the created instance as a status provider in the {@link Systemd} passed as a parameter.
   *
   * @param systemd the {@link Systemd} to send status information to
   */
  SystemdApplicationContextStatusProvider(@SuppressWarnings("SameParameterValue") @NotNull Systemd systemd, int applicationId, String contextId,
      ConfigurableListableBeanFactory factory) {
    this.systemd = Objects.requireNonNull(systemd, "Systemd must not be null");
    this.applicationId = applicationId;
    this.contextId = contextId;
    this.factory = factory;
    this.systemd.addStatusProviders(this);
  }

  private void ensureDefinitionsLoaded() {
    if (!definitionsLoaded) {
      definitions = Arrays.stream(factory.getBeanDefinitionNames())
          .filter(isSingleton(factory))
          .collect(Collectors.toSet());
      definitionsLoaded = true;
    }
  }

  private long getSingletonCount() {
    return definitions.stream()
        .filter(factory::containsSingleton)
        .count();
  }

  private Predicate<? super String> isSingleton(ConfigurableListableBeanFactory factory) {
    return beanName -> factory.getBeanDefinition(beanName).isSingleton();
  }

  @Override
  public @NotNull String status() {
    return systemd.isReady() ? "" : status;
  }

  @Override
  public @Nullable Object postProcessBeforeInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
    if (factory != null) {
      ensureDefinitionsLoaded();
      status = String.format("Application %d (%s): creating bean %d of %d", applicationId, contextId, getSingletonCount(), definitions.size());
      systemd.extendTimeout();
      systemd.updateStatus();
    }
    return bean;
  }

  @Override
  public @Nullable Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
    return bean;
  }
}
