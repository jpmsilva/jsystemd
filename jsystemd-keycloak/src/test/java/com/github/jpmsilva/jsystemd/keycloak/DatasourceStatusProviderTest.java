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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider.Health;
import io.agroal.api.AgroalDataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DatasourceStatusProvider}.
 *
 * @author Joao Silva
 */
class DatasourceStatusProviderTest {

  // the datasource of Keycloak, whose pool opens up to 100 connections, and counts them, as when Keycloak is built with its metrics enabled
  private final AgroalDataSource dataSource = mock(AgroalDataSource.class, RETURNS_DEEP_STUBS);
  // the datasource that is looked up, which there is from the start by default
  private final AtomicReference<@Nullable AgroalDataSource> found = new AtomicReference<>(dataSource);
  private final AtomicInteger lookups = new AtomicInteger();

  @BeforeEach
  void setUp() {
    when(dataSource.getConfiguration().metricsEnabled()).thenReturn(true);
    when(dataSource.getConfiguration().connectionPoolConfiguration().maxSize()).thenReturn(100);
  }

  private DatasourceStatusProvider provider(boolean health) {
    return new DatasourceStatusProvider(() -> {
      lookups.incrementAndGet();
      return found.get();
    }, health);
  }

  private void pool(long active, long awaiting) {
    when(dataSource.getMetrics().activeCount()).thenReturn(active);
    when(dataSource.getMetrics().awaitingCount()).thenReturn(awaiting);
  }

  @Test
  void showsTheConnectionsInUseAndTheThreadsWaiting() {
    pool(7, 3);

    assertThat(provider(false).status()).isEqualTo("Datasource: 7/100 connections, 3 waiting");
  }

  @Test
  void showsNothingWhenThePoolDoesNotCountItsConnections() {
    // as when Keycloak is built without its metrics, in which case the pool reports no connections at all
    when(dataSource.getConfiguration().metricsEnabled()).thenReturn(false);

    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void showsNothingUntilThereIsADatasource() {
    found.set(null);
    DatasourceStatusProvider provider = provider(false);

    assertThat(provider.status()).isEmpty();
    assertThat(provider.status()).isEmpty();
    assertThat(lookups).hasValue(2);

    found.set(dataSource);

    assertThat(provider.status()).isEqualTo("Datasource: 0/100 connections, 0 waiting");
    // looked up only once from then on
    assertThat(provider.status()).isEqualTo("Datasource: 0/100 connections, 0 waiting");
    assertThat(lookups).hasValue(3);
  }

  @Test
  void hasNoDatasourceOutsideOfQuarkus() {
    assertThat(DatasourceStatusProvider.lookup()).isNull();
  }

  @Test
  void isUnhealthyWhileThePoolIsStarved() {
    pool(100, 2);

    Health health = provider(true).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsExactly(entry("activeConnections", 100L), entry("maxConnections", 100), entry("threadsAwaitingConnection", 2L));
  }

  @Test
  void isHealthyWhileThePoolHasConnectionsLeft() {
    pool(99, 0);

    assertThat(provider(true).health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void isHealthyWhileNoThreadWaitsForAConnection() {
    pool(100, 0);

    assertThat(provider(true).health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void isHealthyWhileThreadsWaitForConnectionsThePoolCannotOpen() {
    // as when the database cannot be reached: the connections are not in use
    pool(0, 12);

    assertThat(provider(true).health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyUnlessOptedInto() {
    pool(100, 2);
    DatasourceStatusProvider provider = provider(false);

    assertThat(provider.healthEnabled()).isFalse();
    assertThat(provider.health()).isEqualTo(Health.HEALTHY);
    // not even looked up for the health
    assertThat(lookups).hasValue(0);
  }

  @Test
  void isAlwaysHealthyWhenThePoolDoesNotCountItsConnections() {
    when(dataSource.getConfiguration().metricsEnabled()).thenReturn(false);
    pool(100, 2);

    assertThat(provider(true).health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void isHealthyUntilThereIsADatasource() {
    found.set(null);

    assertThat(provider(true).health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyState() {
    pool(100, 2);
    DatasourceStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    assertThat(provider.health()).isEqualTo(Health.HEALTHY);
    // the status shows the pool as it is
    assertThat(provider.status()).isEqualTo("Datasource: 100/100 connections, 2 waiting");
  }
}
