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
import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Base class for the providers of the message listener containers of a messaging library, those that receive the messages of the listeners of the
 * application: as a {@link StatusProvider}, how many of them run, out of those there are, such as {@code Kafka listeners: 3/3 running}, and as a
 * {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while any of them stopped by itself, if enabled.
 *
 * <p>The containers are those of the listener endpoint registries of the library, which hold the containers of the annotated listener methods, and
 * those that are beans of their own. The registries and the beans are found once all singleton beans are created, so that lazy ones are not created; the
 * containers of the registries are read every time, as listeners can be registered while the application runs. Nothing is reported while there are none.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any container stopped by itself, such as after a fatal error, which leaves the application up, but no longer receiving the messages of
 * its listeners, naming the containers in the health details, under {@code failedListenerContainers}. How a container that stopped by itself is told
 * apart from one that the application stopped, or never started, depends on the library. As some of those failures can resolve by themselves, or be
 * resolved without restarting the application, the unhealthy state can be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}.
 *
 * @param <R> the type of the listener endpoint registries
 * @param <C> the type of the listener containers
 * @author Joao Silva
 */
abstract class ListenerContainerStatusProvider<R, C> extends DelayableHealthProvider implements StatusProvider, SmartInitializingSingleton {

  private final String name;

  private final ConfigurableListableBeanFactory beanFactory;

  private final Class<R> registryType;

  private final Class<C> containerType;

  private final boolean health;

  // set once all singleton beans are created, read by the status updates and the health evaluations
  private volatile Collection<R> registries = List.of();

  private volatile Map<String, C> beans = Map.of();

  /**
   * Creates a new instance that finds the listener containers in the given bean factory.
   *
   * @param name the name of the listener containers in the status, such as {@code Kafka listeners}
   * @param beanFactory the bean factory to find the listener endpoint registries and the listener containers in
   * @param registryType the type of the listener endpoint registries
   * @param containerType the type of the listener containers
   * @param health <code>true</code> to report the application as unhealthy while any listener container stopped by itself
   */
  ListenerContainerStatusProvider(String name, ConfigurableListableBeanFactory beanFactory, Class<R> registryType, Class<C> containerType,
      boolean health) {
    this.name = name;
    this.beanFactory = requireNonNull(beanFactory, "Bean factory must not be null");
    this.registryType = registryType;
    this.containerType = containerType;
    this.health = health;
  }

  // for tests: whether a listener container that stopped by itself reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Finds the listener endpoint registries and the listener containers among the singleton beans that are already created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    registries = List.copyOf(createdBeans(beanFactory, registryType).values());
    beans = Map.copyOf(createdBeans(beanFactory, containerType));
  }

  /**
   * Returns the listener containers by name, sorted by name, so that the order does not depend on the order in which they are registered: those of the
   * registries, under the id of their listener, and those that are beans, under the name of their bean.
   */
  private Map<String, C> containers() {
    Map<String, C> containers = new TreeMap<>(beans);
    for (R registry : registries) {
      for (String id : ids(registry)) {
        C container = container(registry, id);
        if (container != null) {
          containers.put(id, container);
        }
      }
    }
    return containers;
  }

  /**
   * Returns the ids of the listeners of the given registry.
   *
   * @param registry the listener endpoint registry
   * @return the ids of the listeners that have a container in the registry
   */
  abstract Collection<String> ids(R registry);

  /**
   * Returns the listener container of the listener with the given id.
   *
   * @param registry the listener endpoint registry
   * @param id the id of the listener
   * @return the listener container, or <code>null</code> when there is none, such as when it was unregistered meanwhile
   */
  abstract @Nullable C container(R registry, String id);

  /**
   * Tells if the given listener container runs.
   *
   * @param container the listener container
   * @return <code>true</code> if the listener container runs
   */
  abstract boolean running(C container);

  /**
   * Tells if the given listener container stopped by itself, rather than because the application stopped it, or never started it.
   *
   * @param container the listener container
   * @return <code>true</code> if the listener container stopped by itself
   */
  abstract boolean failed(C container);

  /**
   * Returns what else there is to report of the given listener containers, after how many of them run, such as {@code 12 partitions}.
   *
   * @param containers the listener containers
   * @return the other details of the status, if any
   */
  abstract List<String> details(Collection<C> containers);

  @Override
  public String status() {
    Collection<C> containers = containers().values();
    if (containers.isEmpty()) {
      return "";
    }
    List<String> details = new ArrayList<>();
    details.add(String.format(Locale.ROOT, "%d/%d running", containers.stream().filter(this::running).count(), containers.size()));
    details.addAll(details(containers));
    return name + ": " + String.join(", ", details);
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while any listener container stopped by itself, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    if (!health) {
      return Health.HEALTHY;
    }
    List<String> failed = new ArrayList<>();
    containers().forEach((id, container) -> {
      if (failed(container)) {
        failed.add(id);
      }
    });
    return failed.isEmpty() ? Health.HEALTHY : new Health(false, Map.of("failedListenerContainers", failed));
  }
}
