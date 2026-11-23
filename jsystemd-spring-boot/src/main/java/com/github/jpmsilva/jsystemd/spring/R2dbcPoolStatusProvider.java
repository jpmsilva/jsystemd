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
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Provides information regarding the R2DBC connection pools of the application, those of {@code r2dbc-pool}: as a {@link StatusProvider}, for each
 * pool, named after its connection factory bean, the connections in use out of the most the pool opens, and the subscribers waiting for a connection,
 * such as {@code connectionFactory: 7/10 connections, 3 waiting}, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as
 * unhealthy while a pool is starved, if enabled.
 *
 * <p>The pools are those of the {@link ConnectionFactory} beans that are, or wrap, a {@link ConnectionPool}, as Spring Boot sets them up with
 * {@code spring.r2dbc.pool.enabled}, found once all singleton beans are created, so that lazy ones are not created. A pool that is closed is left out.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any pool is starved: all its connections are in use, and subscribers are waiting for one, naming the starved connection factories in
 * the health details, under {@code starvedConnectionFactories}. A starved pool usually means connections leak, as code that takes a connection never
 * gives it back, which only a restart recovers from. As a burst of requests can also starve a pool for a while, the unhealthy state should be delayed,
 * with {@link #setUnhealthyDelay(java.time.Duration)}: the pool must then stay starved, every time the health is evaluated, for that long.
 *
 * <p>A database that cannot be reached does not starve the pools: their subscribers wait for a connection, but the connections are not in use, as the
 * pools cannot open them. That is left for the health indicators of Spring Boot Actuator.
 *
 * @author Joao Silva
 */
public class R2dbcPoolStatusProvider extends DelayableHealthProvider implements StatusProvider, SmartInitializingSingleton {

  private final ConfigurableListableBeanFactory beanFactory;

  private final boolean health;

  // set once all singleton beans are created, read by the status updates and the health evaluations
  private volatile Map<String, ConnectionPool> pools = Map.of();

  /**
   * Creates a new instance that finds the connection factories in the given bean factory.
   *
   * @param beanFactory the bean factory to find the connection factories in
   * @param health <code>true</code> to report the application as unhealthy while any pool is starved
   */
  public R2dbcPoolStatusProvider(ConfigurableListableBeanFactory beanFactory, boolean health) {
    this.beanFactory = requireNonNull(beanFactory, "Bean factory must not be null");
    this.health = health;
  }

  // for tests: whether a starved pool reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Finds the R2DBC connection pools among the connection factory beans that are already created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    Map<String, ConnectionPool> found = new LinkedHashMap<>();
    // a pool wrapped by several connection factory beans, such as a proxy that is also a bean, is only reported once, under the first name
    Set<ConnectionPool> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    createdBeans(beanFactory, ConnectionFactory.class).forEach((name, connectionFactory) -> {
      ConnectionPool pool = unwrap(connectionFactory);
      if (pool != null && seen.add(pool)) {
        found.put(name, pool);
      }
    });
    pools = Collections.unmodifiableMap(found);
  }

  /**
   * Returns the connection pool that the given connection factory is, or wraps, as Spring Boot does for the metrics of the pools.
   */
  private static @Nullable ConnectionPool unwrap(@Nullable Object candidate) {
    if (candidate instanceof ConnectionPool pool) {
      return pool;
    }
    return candidate instanceof Wrapped<?> wrapped ? unwrap(wrapped.unwrap()) : null;
  }

  /**
   * Reads the metrics of each pool that is open.
   */
  private void forEachPool(BiConsumer<String, PoolMetrics> action) {
    pools.forEach((name, pool) -> {
      if (!pool.isDisposed()) {
        pool.getMetrics().ifPresent(metrics -> action.accept(name, metrics));
      }
    });
  }

  @Override
  public String status() {
    List<String> statuses = new ArrayList<>();
    forEachPool((name, metrics) -> statuses.add(String.format(Locale.ROOT, "%s: %d/%d connections, %d waiting", name, metrics.acquiredSize(),
        metrics.getMaxAllocatedSize(), metrics.pendingAcquireSize())));
    return String.join(", ", statuses);
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while any pool is starved, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    if (!health) {
      return Health.HEALTHY;
    }
    List<String> starved = new ArrayList<>();
    forEachPool((name, metrics) -> {
      if (metrics.pendingAcquireSize() > 0 && metrics.acquiredSize() >= metrics.getMaxAllocatedSize()) {
        starved.add(name);
      }
    });
    return starved.isEmpty() ? Health.HEALTHY : new Health(false, Map.of("starvedConnectionFactories", starved));
  }
}
