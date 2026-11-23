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
import io.r2dbc.h2.H2ConnectionFactory;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Wrapped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link R2dbcPoolStatusProvider}, with R2DBC connection pools that really run, on an in-memory database.
 *
 * @author Joao Silva
 */
class R2dbcPoolStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final List<ConnectionPool> pools = new ArrayList<>();
  private final List<Disposable> waiting = new ArrayList<>();

  @AfterEach
  void tearDown() {
    waiting.forEach(Disposable::dispose);
    pools.forEach(ConnectionPool::dispose);
  }

  private ConnectionPool pool(int maxSize) {
    ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(H2ConnectionFactory.inMemory(UUID.randomUUID().toString()))
        .initialSize(0).maxSize(maxSize).build());
    pools.add(pool);
    return pool;
  }

  private R2dbcPoolStatusProvider provider(boolean health) {
    R2dbcPoolStatusProvider provider = new R2dbcPoolStatusProvider(beanFactory, health);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  private static void take(ConnectionFactory connectionFactory) {
    assertThat(Mono.from(connectionFactory.create()).block(Duration.ofSeconds(10))).isNotNull();
  }

  /**
   * Waits for a connection, which keeps waiting until a connection is given back.
   */
  private void waitFor(ConnectionFactory connectionFactory) {
    waiting.add(Mono.from(connectionFactory.create()).subscribe());
  }

  /**
   * A connection factory that wraps another, as those that Spring Boot sets up.
   */
  private record Wrapper(ConnectionFactory wrapped) implements ConnectionFactory, Wrapped<ConnectionFactory> {

    @Override
    public Publisher<? extends Connection> create() {
      return wrapped.create();
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
      return wrapped.getMetadata();
    }

    @Override
    public ConnectionFactory unwrap() {
      return wrapped;
    }
  }

  @Test
  void reportsTheConnectionsInUseAndTheSubscribersWaiting() {
    ConnectionPool pool = pool(2);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(false);

    assertThat(provider.status()).isEqualTo("connectionFactory: 0/2 connections, 0 waiting");

    take(pool);

    assertThat(provider.status()).isEqualTo("connectionFactory: 1/2 connections, 0 waiting");

    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("connectionFactory: 2/2 connections, 1 waiting"));
  }

  @Test
  void reportsEachPoolOnceUnderTheNameOfItsFirstBeanSortedByName() {
    ConnectionPool primary = pool(2);
    ConnectionPool secondary = pool(3);
    beanFactory.registerSingleton("secondary", secondary);
    beanFactory.registerSingleton("primary", primary);
    // a connection factory that is also a bean, which wraps the same pool
    beanFactory.registerSingleton("wrappedPrimary", new Wrapper(primary));
    take(primary);

    assertThat(provider(false).status()).isEqualTo("primary: 1/2 connections, 0 waiting, secondary: 0/3 connections, 0 waiting");
  }

  @Test
  void findsPoolsWrappedByOtherConnectionFactories() {
    ConnectionPool pool = pool(2);
    beanFactory.registerSingleton("connectionFactory", new Wrapper(new Wrapper(pool)));
    take(pool);

    assertThat(provider(false).status()).isEqualTo("connectionFactory: 1/2 connections, 0 waiting");
  }

  @Test
  void reportsNothingForConnectionFactoriesWithoutAPool() {
    beanFactory.registerSingleton("connectionFactory", H2ConnectionFactory.inMemory(UUID.randomUUID().toString()));

    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void leavesOutThePoolsThatAreClosed() {
    ConnectionPool pool = pool(2);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(true);

    pool.dispose();

    assertThat(provider.status()).isEmpty();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void doesNotCreateLazyConnectionFactories() {
    RootBeanDefinition definition = new RootBeanDefinition(ConnectionPool.class, () -> pool(2));
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("lazy", definition);

    assertThat(provider(false).status()).isEmpty();
    assertThat(beanFactory.containsSingleton("lazy")).isFalse();
  }

  @Test
  void isUnhealthyWhileAPoolIsStarved() {
    ConnectionPool pool = pool(1);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(true);

    take(pool);
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    waitFor(pool);

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("starvedConnectionFactories", List.of("connectionFactory")));
    });
  }

  @Test
  void isHealthyWhileThePoolHasConnectionsLeft() {
    ConnectionPool pool = pool(2);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(true);
    take(pool);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    ConnectionPool pool = pool(1);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(false);
    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("connectionFactory: 1/1 connections, 1 waiting"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    ConnectionPool pool = pool(1);
    beanFactory.registerSingleton("connectionFactory", pool);
    R2dbcPoolStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));
    take(pool);
    waitFor(pool);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("connectionFactory: 1/1 connections, 1 waiting"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
