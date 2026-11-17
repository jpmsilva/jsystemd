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
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.jdbc.DataSourceUnwrapper;

/**
 * Provides information regarding the HikariCP connection pools of the application: as a {@link StatusProvider}, for each pool, named after its
 * data source bean, the connections in use out of the most the pool opens, and the threads waiting for a connection, such as
 * {@code dataSource: 7/10 connections, 3 waiting}, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while
 * a pool is starved, if enabled.
 *
 * <p>The pools are those of the {@link DataSource} beans that are, or wrap, a {@link HikariDataSource}, found once all singleton beans are created, so
 * that lazy ones are not created. A data source whose pool is not started yet, as Hikari only starts it on the first
 * connection, is left out until then.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any pool is starved: all its connections are in use, and threads are waiting for one, naming the starved data sources in the health
 * details, under {@code starvedDataSources}. A starved pool usually means connections leak, as code that takes a connection never gives it back, which
 * only a restart recovers from. As a burst of requests can also starve a pool for a while, the unhealthy state should be delayed, with
 * {@link #setUnhealthyDelay(java.time.Duration)}: the pool must then stay starved, every time the health is evaluated, for that long.
 *
 * <p>A database that cannot be reached does not starve the pools: their threads wait for a connection, but the connections are not in use, as the pools
 * cannot open them. That is left for the health indicators of Spring Boot Actuator.
 *
 * @author Joao Silva
 */
public class HikariStatusProvider extends DelayableHealthProvider implements StatusProvider, SmartInitializingSingleton {

  private final ConfigurableListableBeanFactory beanFactory;

  private final boolean health;

  // set once all singleton beans are created, read by the status updates and the health evaluations
  private volatile Map<String, HikariDataSource> pools = Map.of();

  /**
   * Creates a new instance that finds the data sources in the given bean factory.
   *
   * @param beanFactory the bean factory to find the data sources in
   * @param health <code>true</code> to report the application as unhealthy while any pool is starved
   */
  public HikariStatusProvider(ConfigurableListableBeanFactory beanFactory, boolean health) {
    this.beanFactory = requireNonNull(beanFactory, "Bean factory must not be null");
    this.health = health;
  }

  // for tests: whether a starved pool reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Finds the HikariCP connection pools among the data source beans that are already created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    Map<String, HikariDataSource> found = new LinkedHashMap<>();
    // a pool wrapped by several data source beans, such as a proxy that is also a bean, is only reported once, under the first name
    Set<HikariDataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    createdBeans(beanFactory, DataSource.class).forEach((name, dataSource) -> {
      HikariDataSource pool = DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class, HikariDataSource.class);
      if (pool != null && seen.add(pool)) {
        found.put(name, pool);
      }
    });
    pools = Collections.unmodifiableMap(found);
  }

  @Override
  public String status() {
    List<String> statuses = new ArrayList<>();
    pools.forEach((name, pool) -> {
      // null until the pool is started
      HikariPoolMXBean mxBean = pool.getHikariPoolMXBean();
      if (mxBean != null) {
        statuses.add(String.format(Locale.ROOT, "%s: %d/%d connections, %d waiting", name, mxBean.getActiveConnections(), pool.getMaximumPoolSize(),
            mxBean.getThreadsAwaitingConnection()));
      }
    });
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
    pools.forEach((name, pool) -> {
      HikariPoolMXBean mxBean = pool.getHikariPoolMXBean();
      if (mxBean != null && mxBean.getThreadsAwaitingConnection() > 0 && mxBean.getActiveConnections() >= pool.getMaximumPoolSize()) {
        starved.add(name);
      }
    });
    return starved.isEmpty() ? Health.HEALTHY : new Health(false, Map.of("starvedDataSources", starved));
  }
}
