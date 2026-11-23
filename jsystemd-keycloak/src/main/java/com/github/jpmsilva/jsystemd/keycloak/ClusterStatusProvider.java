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

import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import org.infinispan.health.HealthStatus;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jspecify.annotations.Nullable;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link StatusProvider} that provides the cluster of Keycloak, as this node sees it: its nodes, itself included, such as
 * {@code Cluster: 3 nodes}, followed by the health of the cluster when it is not healthy, such as {@code Cluster: 3 nodes, rebalancing} while the caches
 * are being redistributed, as when a node joins or leaves, or {@code Cluster: 2 nodes, degraded}.
 *
 * <p>A node that sees fewer nodes than there are is split from the others, and no longer shares its sessions with them.
 *
 * <p>The cluster is the one of the embedded Infinispan caches of Keycloak, which its nodes form with JGroups. Nothing is reported when Keycloak has
 * none: when its caches are local ({@code cache=local}, as in development mode), or when they are kept in an external Infinispan instead.
 *
 * <p>The cluster is looked up once Keycloak has completed its bootstrap, during which it creates its cache manager.
 *
 * @author Joao Silva
 */
final class ClusterStatusProvider implements StatusProvider {

  private static final Logger logger = LoggerFactory.getLogger(ClusterStatusProvider.class);

  // the session factory of Keycloak, which is null until Keycloak has completed its bootstrap
  private final Supplier<@Nullable KeycloakSessionFactory> bootstrapped;

  // looks up the cluster, which is null when Keycloak has none
  private final Function<KeycloakSessionFactory, @Nullable Cluster> lookup;

  // guarded by this: whether the cluster was looked up, which is only done once, and the cluster, or null if there is none
  private boolean lookedUp;
  @Nullable
  private Cluster cluster;

  /**
   * Creates a new instance, for the cluster of the embedded Infinispan caches of Keycloak.
   *
   * @param bootstrapped provides the session factory of Keycloak, returning null until Keycloak has completed its bootstrap
   */
  ClusterStatusProvider(Supplier<@Nullable KeycloakSessionFactory> bootstrapped) {
    this(bootstrapped, ClusterStatusProvider::lookup);
  }

  /**
   * Creates a new instance.
   *
   * @param bootstrapped provides the session factory of Keycloak, returning null until Keycloak has completed its bootstrap
   * @param lookup looks up the cluster, returning null when Keycloak has none
   */
  ClusterStatusProvider(Supplier<@Nullable KeycloakSessionFactory> bootstrapped, Function<KeycloakSessionFactory, @Nullable Cluster> lookup) {
    this.bootstrapped = requireNonNull(bootstrapped, "Bootstrapped must not be null");
    this.lookup = requireNonNull(lookup, "Lookup must not be null");
  }

  /**
   * The cluster of Keycloak, as this node sees it.
   */
  interface Cluster {

    /**
     * Counts the nodes of the cluster, this one included.
     *
     * @return the number of nodes, or 0 when this node is in no cluster
     */
    int nodes();

    /**
     * Tells the health of the cluster.
     *
     * @return the health of the cluster
     */
    HealthStatus health();
  }

  /**
   * Looks up the cluster of the embedded Infinispan caches of Keycloak, through their cache manager, from a cache that every setup has.
   *
   * @param sessionFactory the session factory of Keycloak
   * @return the cluster, or null when the caches are not clustered
   */
  static @Nullable Cluster lookup(KeycloakSessionFactory sessionFactory) {
    try (KeycloakSession session = sessionFactory.create()) {
      InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
      if (provider == null) {
        return null;
      }
      EmbeddedCacheManager cacheManager = provider.getCache(InfinispanConnectionProvider.REALM_CACHE_NAME).getCacheManager();
      if (!cacheManager.getCacheManagerConfiguration().isClustered()) {
        return null;
      }
      return new Cluster() {
        @Override
        public int nodes() {
          // null when there is no cluster
          List<?> members = cacheManager.getMembers();
          return members == null ? 0 : members.size();
        }

        @Override
        public HealthStatus health() {
          return cacheManager.getHealth().getClusterHealth().getHealthStatus();
        }
      };
    }
  }

  private synchronized @Nullable Cluster cluster() {
    if (!lookedUp) {
      KeycloakSessionFactory sessionFactory = bootstrapped.get();
      if (sessionFactory == null) {
        return null;
      }
      lookedUp = true;
      try {
        cluster = lookup.apply(sessionFactory);
      } catch (RuntimeException | LinkageError e) {
        // as in a Keycloak without embedded Infinispan caches, which all versions so far have
        logger.debug("Not reporting the cluster, as it cannot be looked up", e);
      }
    }
    return cluster;
  }

  @Override
  public String status() {
    Cluster cluster = cluster();
    if (cluster == null) {
      return "";
    }
    try {
      int nodes = cluster.nodes();
      if (nodes == 0) {
        return "";
      }
      String status = String.format(Locale.ROOT, "Cluster: %d %s", nodes, nodes == 1 ? "node" : "nodes");
      HealthStatus health = cluster.health();
      return health == HealthStatus.HEALTHY ? status : status + ", " + describe(health);
    } catch (RuntimeException e) {
      // as once the cache manager stops, while Keycloak shuts down
      logger.debug("Not reporting the cluster, as its cache manager does not tell", e);
      return "";
    }
  }

  // such as "rebalancing" for HEALTHY_REBALANCING, or "degraded" for DEGRADED
  private static String describe(HealthStatus health) {
    return health.name().toLowerCase(Locale.ROOT).replace("healthy_", "").replace('_', ' ');
  }
}
