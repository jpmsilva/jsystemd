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

package com.github.jpmsilva.jsystemd.keycloak;

import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides information regarding the connection pool of the datasource of Keycloak, the one of its database: as a {@link StatusProvider}, the
 * connections in use out of the most the pool opens, and the threads waiting for a connection, such as {@code Datasource: 7/100 connections, 3 waiting},
 * and as a {@link HealthProvider}, for the systemd watchdog, reporting Keycloak as unhealthy while the pool is starved, if enabled.
 *
 * <p>The pool only counts its connections when Keycloak is built with its metrics enabled ({@code metrics-enabled}): nothing is reported otherwise,
 * and Keycloak is then always healthy.
 *
 * <p>The health is enabled on its own, as it lets systemd restart Keycloak: while disabled, Keycloak is always healthy. When enabled, it is unhealthy
 * while the pool is starved: all its connections are in use, and threads are waiting for one, reporting both in the health details. A starved pool
 * usually means connections leak, or are all held by requests that hang, which only a restart recovers from. As a burst of requests can also starve the
 * pool for a while, the unhealthy state should be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}: the pool must then stay starved, every
 * time the health is evaluated, for that long.
 *
 * <p>A database that cannot be reached does not starve the pool: its threads wait for a connection, but the connections are not in use, as the pool
 * cannot open them. That is left for the readiness health checks of Keycloak.
 *
 * @author Joao Silva
 */
final class DatasourceStatusProvider extends DelayableHealthProvider implements StatusProvider {

  private static final Logger logger = LoggerFactory.getLogger(DatasourceStatusProvider.class);

  // looks up the datasource, which is null until Keycloak has created it
  private final Supplier<@Nullable AgroalDataSource> lookup;

  private final boolean health;

  // set once found, read by the status updates and the health evaluations
  @Nullable
  private volatile AgroalDataSource dataSource;

  /**
   * Creates a new instance.
   *
   * @param lookup looks up the datasource of Keycloak, returning null while there is none
   * @param health <code>true</code> to report Keycloak as unhealthy while the pool is starved
   */
  DatasourceStatusProvider(Supplier<@Nullable AgroalDataSource> lookup, boolean health) {
    this.lookup = requireNonNull(lookup, "Lookup must not be null");
    this.health = health;
  }

  // for tests: whether a starved pool reports Keycloak as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Looks up the datasource of Keycloak, its default one.
   *
   * @return the datasource, or null when there is none, as outside of Quarkus, as in tests
   */
  static @Nullable AgroalDataSource lookup() {
    try {
      ArcContainer container = Arc.container();
      // a lookup of a bean that is not there would warn, unlike asking if it is there
      if (container == null || container.beanManager().getBeans(AgroalDataSource.class).isEmpty()) {
        return null;
      }
      InstanceHandle<AgroalDataSource> handle = container.instance(AgroalDataSource.class);
      return handle.isAvailable() ? handle.get() : null;
    } catch (LinkageError e) {
      // a Keycloak without Agroal or ArC, which all versions so far have
      logger.debug("Not reporting the datasource, as it cannot be looked up", e);
      return null;
    }
  }

  // the datasource, when it counts its connections
  private synchronized @Nullable AgroalDataSource counting() {
    AgroalDataSource dataSource = this.dataSource;
    if (dataSource == null) {
      dataSource = lookup.get();
      if (dataSource == null) {
        return null;
      }
      this.dataSource = dataSource;
      if (health && !dataSource.getConfiguration().metricsEnabled()) {
        logger.warn("The health of the datasource is enabled, but its connection pool does not count its connections, so Keycloak is always healthy: "
            + "build Keycloak with its metrics enabled (metrics-enabled=true)");
      }
    }
    return dataSource.getConfiguration().metricsEnabled() ? dataSource : null;
  }

  @Override
  public String status() {
    AgroalDataSource dataSource = counting();
    if (dataSource == null) {
      return "";
    }
    AgroalDataSourceMetrics metrics = dataSource.getMetrics();
    return String.format(Locale.ROOT, "Datasource: %d/%d connections, %d waiting", metrics.activeCount(),
        dataSource.getConfiguration().connectionPoolConfiguration().maxSize(), metrics.awaitingCount());
  }

  /**
   * Provides the health of Keycloak, without the delay: unhealthy while the pool is starved, if enabled.
   *
   * @return the health of Keycloak
   */
  @Override
  protected Health evaluateHealth() {
    AgroalDataSource dataSource = health ? counting() : null;
    if (dataSource == null) {
      return Health.HEALTHY;
    }
    AgroalDataSourceMetrics metrics = dataSource.getMetrics();
    long active = metrics.activeCount();
    int max = dataSource.getConfiguration().connectionPoolConfiguration().maxSize();
    long awaiting = metrics.awaitingCount();
    if (awaiting <= 0 || active < max) {
      return Health.HEALTHY;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("activeConnections", active);
    details.put("maxConnections", max);
    details.put("threadsAwaitingConnection", awaiting);
    return new Health(false, details);
  }
}
