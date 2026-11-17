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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import com.github.jpmsilva.jsystemd.HealthProvider;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Tests for {@link HikariStatusProvider}, with HikariCP connection pools that really run, on an in-memory database.
 *
 * @author Joao Silva
 */
class HikariStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final List<HikariDataSource> pools = new ArrayList<>();
  private final List<Connection> connections = new ArrayList<>();
  private final ExecutorService waiting = Executors.newCachedThreadPool();

  @AfterEach
  void tearDown() throws SQLException, InterruptedException {
    for (Connection connection : connections) {
      connection.close();
    }
    waiting.shutdownNow();
    assertThat(waiting.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    for (HikariDataSource pool : pools) {
      pool.close();
    }
  }

  private HikariDataSource pool(int maximumPoolSize) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID());
    pool.setMaximumPoolSize(maximumPoolSize);
    pool.setMinimumIdle(0);
    pool.setConnectionTimeout(30_000);
    pools.add(pool);
    return pool;
  }

  private HikariStatusProvider provider(boolean health) {
    HikariStatusProvider provider = new HikariStatusProvider(beanFactory, health);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  private void take(DataSource dataSource) throws SQLException {
    connections.add(dataSource.getConnection());
  }

  /**
   * Waits for a connection on another thread, which keeps waiting until a connection is given back.
   */
  private void waitFor(DataSource dataSource) {
    waiting.execute(() -> {
      try (Connection ignored = dataSource.getConnection()) {
        // only waiting matters
      } catch (SQLException e) {
        // interrupted once the test ends
      }
    });
  }

  @Test
  void reportsTheConnectionsInUseAndTheThreadsWaiting() throws SQLException {
    HikariDataSource pool = pool(2);
    beanFactory.registerSingleton("dataSource", pool);
    HikariStatusProvider provider = provider(false);

    take(pool);

    assertThat(provider.status()).isEqualTo("dataSource: 1/2 connections, 0 waiting");

    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("dataSource: 2/2 connections, 1 waiting"));
  }

  @Test
  void reportsNothingUntilThePoolIsStarted() {
    beanFactory.registerSingleton("dataSource", pool(2));

    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void reportsEachPoolOnceUnderTheNameOfItsFirstBeanSortedByName() throws SQLException {
    HikariDataSource primary = pool(2);
    HikariDataSource secondary = pool(3);
    beanFactory.registerSingleton("secondary", secondary);
    beanFactory.registerSingleton("primary", primary);
    // a proxy that is also a bean, which wraps the same pool
    beanFactory.registerSingleton("transactionAwarePrimary", new TransactionAwareDataSourceProxy(primary));
    take(primary);
    take(secondary);

    assertThat(provider(false).status()).isEqualTo("primary: 1/2 connections, 0 waiting, secondary: 1/3 connections, 0 waiting");
  }

  @Test
  void findsPoolsWrappedByOtherDataSources() throws SQLException {
    HikariDataSource pool = pool(2);
    beanFactory.registerSingleton("dataSource", new TransactionAwareDataSourceProxy(pool));
    take(pool);

    assertThat(provider(false).status()).isEqualTo("dataSource: 1/2 connections, 0 waiting");
  }

  @Test
  void doesNotCreateLazyDataSources() {
    RootBeanDefinition definition = new RootBeanDefinition(HikariDataSource.class);
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("lazy", definition);

    assertThat(provider(false).status()).isEmpty();
    assertThat(beanFactory.containsSingleton("lazy")).isFalse();
  }

  @Test
  void isUnhealthyWhileAPoolIsStarved() throws SQLException {
    HikariDataSource pool = pool(1);
    beanFactory.registerSingleton("dataSource", pool);
    HikariStatusProvider provider = provider(true);

    take(pool);
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    waitFor(pool);

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("starvedDataSources", List.of("dataSource")));
    });
  }

  @Test
  void isHealthyWhileThePoolHasConnectionsLeft() throws SQLException {
    HikariDataSource pool = pool(2);
    beanFactory.registerSingleton("dataSource", pool);
    HikariStatusProvider provider = provider(true);
    take(pool);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() throws SQLException {
    HikariDataSource pool = pool(1);
    beanFactory.registerSingleton("dataSource", pool);
    HikariStatusProvider provider = provider(false);
    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("dataSource: 1/1 connections, 1 waiting"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() throws SQLException {
    HikariDataSource pool = pool(1);
    beanFactory.registerSingleton("dataSource", pool);
    HikariStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));
    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("dataSource: 1/1 connections, 1 waiting"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
