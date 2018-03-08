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
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.Order;

@Order(-4000)
public class SystemdNotifyApplicationContextStatusProvider implements SystemdNotifyStatusProvider,
    BeanPostProcessor {

  private final Systemd systemd;
  private ConfigurableListableBeanFactory factory;
  private Map<String, BeanDefinition> definitions;
  private String status = "";

  SystemdNotifyApplicationContextStatusProvider(Systemd systemd) {
    this.systemd = systemd;
    this.systemd.addStatusProviders(this);
  }

  void setFactory(ConfigurableListableBeanFactory factory) {
    this.factory = factory;
  }

  @Override
  public String status() {
    return systemd.isReady() ? "" : status;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName)
      throws BeansException {
    if (factory != null) {
      ensureDefinitionsLoaded();
      status = String.format("Creating bean %d of %d", getSingletonCount(), definitions.size());
      systemd.extendTimeout();
      systemd.updateStatus();
    }
    return bean;
  }

  private void ensureDefinitionsLoaded() {
    if (definitions == null) {
      definitions = Arrays.stream(factory.getBeanDefinitionNames())
          .filter(isSingleton(factory))
          .collect(Collectors.toMap(Function.identity(), factory::getBeanDefinition));
    }
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }

  private int getSingletonCount() {
    return definitions.entrySet().stream()
        .filter(t -> factory.containsSingleton(t.getKey()))
        .map(Entry::getKey)
        .collect(Collectors.toList()).size();
  }

  private Predicate<? super String> isSingleton(ConfigurableListableBeanFactory factory) {
    return beanName -> factory.getBeanDefinition(beanName).isSingleton();
  }

}
