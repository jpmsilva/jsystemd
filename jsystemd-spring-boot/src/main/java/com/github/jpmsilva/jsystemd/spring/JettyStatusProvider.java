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

import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
 * Implementation of {@link StatusProvider} that provides information regarding the embedded Jetty servers of the application.
 *
 * <p>For each server, named after the port it listens on, it reports the open connections, out of the maximum the server accepts when limited, and, when
 * the requests are handled by a thread pool rather than by virtual threads, the threads running requests out of the threads in the pool, such as
 * {@code jetty-8080: 12/1000 connections, 3/10 threads}. The threads Jetty keeps for itself, to accept connections and wait for their data, are left out of
 * the threads running requests. A server that is not started is left out.
 *
 * <p>The servers are found as they start, from their {@link WebServerInitializedEvent}: the server of the application, as well as the server of the
 * management endpoints when they use a port of their own. Nothing is reported until then.
 *
 * @author Joao Silva
 */
public class JettyStatusProvider implements StatusProvider, ApplicationListener<WebServerInitializedEvent> {

  // added by the event listener, read by the status updates
  private final CopyOnWriteArrayList<Server> servers = new CopyOnWriteArrayList<>();

  /**
   * Create a new JettyStatusProvider.
   */
  public JettyStatusProvider() {
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
}
