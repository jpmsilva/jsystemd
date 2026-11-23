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

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Provides information regarding the embedded Jetty servers of the application: as a {@link StatusProvider}, their connections and threads, and as a
 * {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while all the threads of a server are taken and requests
 * wait for one, if enabled.
 *
 * <p>For each server, named after the port it listens on, it reports the open connections, out of the maximum the server accepts when limited, and, when
 * the requests are handled by a thread pool rather than by virtual threads, the threads running requests out of the threads in the pool, such as
 * {@code jetty-8080: 12/1000 connections, 3/10 threads}. The threads Jetty keeps for itself, to accept connections and wait for their data, are left out of
 * the threads running requests. A server that is not started is left out.
 *
 * <p>The servers are found as they start, from their {@link WebServerInitializedEvent}: the server of the application, as well as the server of the
 * management endpoints when they use a port of their own. Nothing is reported until then.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any server is saturated: its pool has all the threads it can have, the most it grows to rather than those it has so far, none of them
 * is idle, and requests are waiting for one, naming the saturated servers in the health details, under {@code saturatedServers}. A server that stays
 * saturated usually means that its requests hang, on something that never answers, which only a restart recovers from. As a burst of requests can also
 * saturate a server for a while, the unhealthy state should be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}: a server must then stay
 * saturated, every time the health is evaluated, for that long.
 *
 * <p>The threads running requests, as shown in the status, do not tell if a server is saturated: Jetty may keep one of its threads in reserve, to wait
 * for the data of the connections, rather than run a request with it, so that requests can be waiting while the status shows a thread to spare.
 *
 * <p>A server whose requests are handled by virtual threads has no thread pool to exhaust, and is never saturated.
 *
 * @author Joao Silva
 */
public class JettyStatusProvider extends DelayableHealthProvider implements StatusProvider, ApplicationListener<WebServerInitializedEvent> {

  private final boolean health;

  // added by the event listener, read by the status updates and the health evaluations
  private final CopyOnWriteArrayList<Server> servers = new CopyOnWriteArrayList<>();

  /**
   * Create a new JettyStatusProvider, which always reports the application as healthy.
   */
  public JettyStatusProvider() {
    this(false);
  }

  /**
   * Create a new JettyStatusProvider.
   *
   * @param health <code>true</code> to report the application as unhealthy while all the threads of a server are taken and requests wait for one
   */
  public JettyStatusProvider(boolean health) {
    this.health = health;
  }

  // for tests: whether a saturated server reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Adds the server of the event to the servers that are reported, if it is a Jetty server.
   *
   * @param event the event of a web server that started
   */
  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (event.getWebServer() instanceof JettyWebServer server) {
      servers.addIfAbsent(server.getServer());
    }
  }

  @Override
  public String status() {
    // sorted by name, so that the order does not depend on the order in which the servers start
    Map<String, String> statuses = new TreeMap<>();
    for (Server server : servers) {
      String name = name(server);
      if (name != null) {
        statuses.put(name, name + ": " + String.join(", ", details(server)));
      }
    }
    return String.join(", ", statuses.values());
  }

  /**
   * Returns the name of the server, after the ports its connectors listen on, or <code>null</code> if none is listening.
   */
  private static @Nullable String name(Server server) {
    List<String> ports = new ArrayList<>();
    for (Connector connector : server.getConnectors()) {
      // -1 while the connector is not started
      if (connector instanceof NetworkConnector network && network.getLocalPort() > 0) {
        ports.add(String.valueOf(network.getLocalPort()));
      }
    }
    return ports.isEmpty() ? null : "jetty-" + String.join("+", ports);
  }

  private static List<String> details(Server server) {
    List<String> details = new ArrayList<>();
    int connections = 0;
    for (Connector connector : server.getConnectors()) {
      if (connector instanceof AbstractConnector abstractConnector) {
        connections += abstractConnector.getConnectedEndPoints().size();
      }
    }
    // the maximum is only set with server.jetty.max-connections, which limits the connections of all the connectors of the server together
    NetworkConnectionLimit limit = server.getBean(NetworkConnectionLimit.class);
    details.add(limit != null ? String.format(Locale.ROOT, "%d/%d connections", connections, limit.getMaxNetworkConnectionCount())
        : String.format(Locale.ROOT, "%d connections", connections));
    // virtual threads are not pooled, so there is nothing to report on them; the threads leased by Jetty, the acceptors and selectors, never run requests
    if (server.getThreadPool() instanceof QueuedThreadPool pool) {
      details.add(String.format(Locale.ROOT, "%d/%d threads", pool.getUtilizedThreads(), pool.getThreads() - pool.getLeasedThreads()));
    }
    return details;
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while all the threads of any server are taken and requests wait for one, if
   * enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    if (!health) {
      return Health.HEALTHY;
    }
    // sorted by name, as the status
    Set<String> saturated = new TreeSet<>();
    for (Server server : servers) {
      String name = name(server);
      // the same thread pool as the status; virtual threads are not pooled, so a server that uses them is never saturated
      if (name != null && server.getThreadPool() instanceof QueuedThreadPool pool && saturated(pool)) {
        saturated.add(name);
      }
    }
    return saturated.isEmpty() ? Health.HEALTHY : new Health(false, Map.of("saturatedServers", List.copyOf(saturated)));
  }

  // requests are waiting for a thread, and none will come: a pool that can still grow starts one, and an idle one is about to take a request; the threads
  // running requests are not compared to those the pool can have, as Jetty may keep one in reserve rather than run a request with it
  private static boolean saturated(QueuedThreadPool pool) {
    return pool.getQueueSize() > 0 && pool.getIdleThreads() == 0 && pool.getThreads() >= pool.getMaxThreads();
  }
}
