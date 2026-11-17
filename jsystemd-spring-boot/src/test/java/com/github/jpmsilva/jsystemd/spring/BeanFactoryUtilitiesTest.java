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

import static com.github.jpmsilva.jsystemd.spring.BeanFactoryUtilities.createdBeans;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Tests for {@link BeanFactoryUtilities}.
 *
 * @author Joao Silva
 */
class BeanFactoryUtilitiesTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

  @Test
  void findsTheCreatedSingletonsOfTheTypeSortedByName() {
    beanFactory.registerSingleton("second", new StringBuilder("second"));
    beanFactory.registerSingleton("first", new StringBuilder("first"));
    beanFactory.registerSingleton("other", 1);

    assertThat(createdBeans(beanFactory, StringBuilder.class).keySet()).containsExactly("first", "second");
  }

  @Test
  void leavesOutLazySingletonsWithoutCreatingThem() {
    RootBeanDefinition definition = new RootBeanDefinition(StringBuilder.class);
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("lazy", definition);

    assertThat(createdBeans(beanFactory, StringBuilder.class)).isEmpty();
    assertThat(beanFactory.containsSingleton("lazy")).isFalse();
  }

  @Test
  void leavesOutTheProductsOfFactoryBeansThatAreNotCreatedWithoutCreatingThem() {
    AtomicInteger created = new AtomicInteger();
    RootBeanDefinition definition = new RootBeanDefinition(Factory.class);
    definition.getConstructorArgumentValues().addGenericArgumentValue(created);
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("factory", definition);

    assertThat(createdBeans(beanFactory, StringBuilder.class)).isEmpty();
    assertThat(created).hasValue(0);
  }

  @Test
  void findsAndCreatesTheProductsOfFactoryBeansThatAreCreated() {
    AtomicInteger created = new AtomicInteger();
    RootBeanDefinition definition = new RootBeanDefinition(Factory.class);
    definition.getConstructorArgumentValues().addGenericArgumentValue(created);
    beanFactory.registerBeanDefinition("factory", definition);
    beanFactory.preInstantiateSingletons();

    assertThat(createdBeans(beanFactory, StringBuilder.class)).containsOnlyKeys("factory");
    assertThat(created).hasValue(1);
  }

  /**
   * A factory bean of string builders, which counts the ones it creates.
   */
  static class Factory implements FactoryBean<StringBuilder> {

    private final AtomicInteger created;

    Factory(AtomicInteger created) {
      this.created = created;
    }

    @Override
    public StringBuilder getObject() {
      created.incrementAndGet();
      return new StringBuilder("product");
    }

    @Override
    public Class<?> getObjectType() {
      return StringBuilder.class;
    }
  }
}
