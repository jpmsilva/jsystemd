/*
 * Copyright 2018-2026 Joao Silva
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

import com.github.jpmsilva.jsystemd.StartingStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the bean creation progress of a bean factory. It only reports while the
 * application starts up, as a {@link StartingStatusProvider}.
 *
 * @author Joao Silva
 * @see BeanPostProcessor
 */
public class ApplicationContextStatusProvider implements StartingStatusProvider, BeanPostProcessor {

  /**
   * The minimum interval between two notifications sent to systemd, as a bean is created far more often than systemd needs to hear about it. It is kept well
   * below the amount by which the startup timeout is extended, so that the timeout keeps being extended while beans are being created.
   */
  static final long NOTIFY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final ConfigurableListableBeanFactory factory;
  private final LongSupplier nanoTime;
  // when systemd was last notified, initially one interval in the past, so that the first bean created is notified right away
  private final AtomicLong lastNotified;
  // the names of the singletons to create, loaded when the first bean is created, and read by the periodic status update task too
  @Nullable
  private volatile Set<String> definitions;

  /**
   * Creates a new instance, which sends the status to systemd as the beans are created.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   *
   * @param factory the bean factory of the application context, whose bean creation progress is reported
   */
  public ApplicationContextStatusProvider(ConfigurableListableBeanFactory factory) {
    this(factory, System::nanoTime);
  }

  /**
   * Creates a new instance with the given source of time, for tests.
   */
  ApplicationContextStatusProvider(ConfigurableListableBeanFactory factory, LongSupplier nanoTime) {
    this.factory = factory;
    this.nanoTime = nanoTime;
    this.lastNotified = new AtomicLong(nanoTime.getAsLong() - NOTIFY_INTERVAL_NANOS);
  }

  private void ensureDefinitionsLoaded() {
    if (definitions == null) {
      definitions = Arrays.stream(factory.getBeanDefinitionNames())
          .filter(isCreatedOnStartup(factory))
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  // the beans created eagerly while the context starts: neither abstract definitions, which are never created, nor lazy ones, which are only created once
  // used, if ever, as with spring.main.lazy-initialization, so that the count reaches the total; merged, as a child definition inherits these from its parent
  private Predicate<? super String> isCreatedOnStartup(ConfigurableListableBeanFactory factory) {
    return beanName -> {
      BeanDefinition definition = factory.getMergedBeanDefinition(beanName);
      return definition.isSingleton() && !definition.isAbstract() && !definition.isLazyInit();
    };
  }

  /**
   * Returns the bean creation progress, counting the singletons created so far, out of those created on startup: lazy ones, which may only be created
   * once used, are left out, and nothing is reported when all of them are lazy. They are counted only when the status is read, that is, when systemd is
   * notified (at most once a second while beans are being created) and on each periodic status update, rather than every time a bean is created.
   */
  @Override
  public String status() {
    Set<String> definitions = this.definitions;
    if (definitions == null || definitions.isEmpty()) {
      return "";
    }
    long created = definitions.stream().filter(factory::containsSingleton).count();
    return String.format(Locale.ROOT, "Creating bean %d of %d", created, definitions.size());
  }

  /**
   * Notifies systemd of the bean creation progress, at most once a second, while the application starts up: the beans created afterwards, such as
   * prototype or request scoped beans, are not part of the startup, and must neither extend the shutdown timeout once the application is stopping, nor
   * update the status on the threads that create them, such as the threads that serve requests.
   */
  @Override
  public @Nullable Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    // ready once stopping too, as the application may stop before it is ready
    if (factory != null && !Systemd.instance().isReady()) {
      ensureDefinitionsLoaded();
      if (shouldNotify()) {
        Systemd systemd = Systemd.instance();
        systemd.extendTimeout();
        systemd.updateStatus();
      }
    }
    return bean;
  }

  // claims the notification when the interval has passed, so that only one of several threads creating beans at the same time notifies systemd
  private boolean shouldNotify() {
    long now = nanoTime.getAsLong();
    long last = lastNotified.get();
    return now - last >= NOTIFY_INTERVAL_NANOS && lastNotified.compareAndSet(last, now);
  }

  @Override
  public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }
}
