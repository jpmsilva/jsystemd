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
import static org.mockito.Mockito.mock;

import com.github.jpmsilva.jsystemd.keycloak.ClusterStatusProvider.Cluster;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.infinispan.health.HealthStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Tests for {@link ClusterStatusProvider}.
 *
 * @author Joao Silva
 */
class ClusterStatusProviderTest {

  private final KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
  // the session factory of Keycloak, which has completed its bootstrap by default
  private final AtomicReference<@Nullable KeycloakSessionFactory> bootstrapped = new AtomicReference<>(sessionFactory);
  // the nodes and the health of the cluster: a healthy one of three nodes by default
  private final AtomicInteger nodes = new AtomicInteger(3);
  private final AtomicReference<HealthStatus> health = new AtomicReference<>(HealthStatus.HEALTHY);
  // if the cache manager of the cluster has stopped, which it has not by default
  private final AtomicBoolean stopped = new AtomicBoolean();
  // the cluster that is looked up, which Keycloak has by default
  private final AtomicReference<@Nullable Cluster> found = new AtomicReference<>(new Cluster() {
    @Override
    public int nodes() {
      if (stopped.get()) {
        throw new IllegalStateException("stopped");
      }
      return nodes.get();
    }

    @Override
    public HealthStatus health() {
      return health.get();
    }
  });
  private final AtomicInteger lookups = new AtomicInteger();
  private final ClusterStatusProvider provider = new ClusterStatusProvider(bootstrapped::get, sessionFactory -> {
    lookups.incrementAndGet();
    assertThat(sessionFactory).isSameAs(this.sessionFactory);
    return found.get();
  });

  @Test
  void showsTheNodesOfTheCluster() {
    assertThat(provider.status()).isEqualTo("Cluster: 3 nodes");
  }

  @Test
  void showsASingleNode() {
    // as the first node of a cluster, or one that is split from the others
    nodes.set(1);

    assertThat(provider.status()).isEqualTo("Cluster: 1 node");
  }

  @ParameterizedTest
  @CsvSource({"HEALTHY_REBALANCING,rebalancing", "DEGRADED,degraded", "INITIALIZING,initializing", "FAILED,failed"})
  void showsTheHealthOfAClusterThatIsNotHealthy(HealthStatus health, String shown) {
    this.health.set(health);

    assertThat(provider.status()).isEqualTo("Cluster: 3 nodes, " + shown);
  }

  @Test
  void showsNothingWithoutACluster() {
    // as with local caches (cache=local), or an external Infinispan
    found.set(null);

    assertThat(provider.status()).isEmpty();
    assertThat(provider.status()).isEmpty();
    // not looked up again
    assertThat(lookups).hasValue(1);
  }

  @Test
  void showsNothingWithoutNodes() {
    nodes.set(0);

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void onlyLooksUpTheClusterOnceKeycloakHasCompletedItsBootstrap() {
    // Keycloak creates its cache manager while it bootstraps, which a lookup would do before it
    bootstrapped.set(null);

    assertThat(provider.status()).isEmpty();
    assertThat(lookups).hasValue(0);

    bootstrapped.set(sessionFactory);

    assertThat(provider.status()).isEqualTo("Cluster: 3 nodes");
    assertThat(provider.status()).isEqualTo("Cluster: 3 nodes");
    assertThat(lookups).hasValue(1);
  }

  @Test
  void showsNothingWhenTheClusterCannotBeLookedUp() {
    ClusterStatusProvider provider = new ClusterStatusProvider(bootstrapped::get, sessionFactory -> {
      lookups.incrementAndGet();
      throw new IllegalStateException("no caches");
    });

    assertThat(provider.status()).isEmpty();
    assertThat(provider.status()).isEmpty();
    // not looked up again
    assertThat(lookups).hasValue(1);
  }

  @Test
  void showsNothingWithoutTheClassesOfInfinispan() {
    ClusterStatusProvider provider = new ClusterStatusProvider(bootstrapped::get, sessionFactory -> {
      throw new NoClassDefFoundError("org/infinispan/manager/EmbeddedCacheManager");
    });

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void showsNothingOnceTheClusterNoLongerTells() {
    assertThat(provider.status()).isEqualTo("Cluster: 3 nodes");

    // as once the cache manager stops, while Keycloak shuts down
    stopped.set(true);

    assertThat(provider.status()).isEmpty();
  }
}
