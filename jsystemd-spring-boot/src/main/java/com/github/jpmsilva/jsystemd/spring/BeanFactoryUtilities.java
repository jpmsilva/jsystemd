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

package com.github.jpmsilva.jsystemd.spring;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Utilities to find beans for the status providers. Not meant to be used directly or instantiated.
 *
 * @author Joao Silva
 */
final class BeanFactoryUtilities {

  private BeanFactoryUtilities() {
  }

  /**
   * Returns the singleton beans of the given type that are already created, by name, sorted by name. Lazy beans that are not created yet are left out, and
   * so are the products of factory beans that are not created yet: no bean is created to find its type. The products of factory beans that are already
   * created are included, and are created if they are not yet, as for any lookup of the bean.
   *
   * @param beanFactory the bean factory to find the beans in
   * @param type the type of the beans
   * @param <T> the type of the beans
   * @return the beans of the given type that are already created, by name
   */
  static <T> Map<String, T> createdBeans(ConfigurableListableBeanFactory beanFactory, Class<T> type) {
    Map<String, T> beans = new TreeMap<>();
    for (String name : beanFactory.getBeanNamesForType(type, false, false)) {
      if (beanFactory.containsSingleton(name)) {
        beans.put(name, beanFactory.getBean(name, type));
      }
    }
    return beans;
  }
}
