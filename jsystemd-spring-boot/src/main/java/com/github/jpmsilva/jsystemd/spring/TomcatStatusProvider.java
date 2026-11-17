/*
 * Copyright 2018-2026 Joao Silva
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
import java.util.concurrent.Executor;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the connectors of the embedded Tomcat servers of the application.
 *
 * <p>For each connector, it reports the open connections out of the maximum the connector accepts, and, when the requests are handled by a thread pool
 * rather than by virtual threads, the busy threads out of the threads in the pool, such as {@code http-nio-8080: 8/8192 connections, 3/10 threads}. A
 * connector that accepts unlimited connections only reports its threads, and one that is not started is left out.
 *
 * <p>The servers are found as they start, from their {@link WebServerInitializedEvent}: the server of the application, as well as the server of the
 * management endpoints when they use a port of their own. Nothing is reported until then.
 *
 * @author Joao Silva
 */
public class TomcatStatusProvider implements StatusProvider, ApplicationListener<WebServerInitializedEvent> {

  // added by the event listener, read by the status updates
  private final CopyOnWriteArrayList<Tomcat> servers = new CopyOnWriteArrayList<>();

  /**
   * Create a new TomcatStatusProvider.
   */
  public TomcatStatusProvider() {
  }

  /**
   * Adds the server of the event to the servers whose connectors are reported, if it is a Tomcat server.
   *
   * @param event the event of a web server that started
   */
  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (event.getWebServer() instanceof TomcatWebServer server) {
      servers.addIfAbsent(server.getTomcat());
    }
  }

  @Override
  public String status() {
    // sorted by name, so that the order does not depend on the order in which the servers start
    Map<String, String> statuses = new TreeMap<>();
    for (Tomcat server : servers) {
      for (Connector connector : server.getService().findConnectors()) {
        if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
          String name = unquote(protocol.getName());
          String status = status(protocol);
          if (status != null) {
            statuses.put(name, name + ": " + status);
          }
        }
      }
    }
    return String.join(", ", statuses.values());
  }

  private static @Nullable String status(AbstractProtocol<?> protocol) {
    List<String> details = new ArrayList<>();
    // -1 while the connector is not started, or when it accepts unlimited connections
    long connections = protocol.getConnectionCount();
    if (connections >= 0) {
      // the count includes a slot the connector holds while waiting for the next connection, which is not an open connection; the connector no longer holds
      // it once the maximum is reached, so this then reports one connection less than open
      details.add(String.format(Locale.ROOT, "%d/%d connections", Math.max(0, connections - 1), protocol.getMaxConnections()));
    }
    // the same thread pools as Tomcat reports in its own thread pool MBeans; virtual threads are not pooled, so there is nothing to report on them
    Executor executor = protocol.getExecutor();
    if (executor instanceof ThreadPoolExecutor pool) {
      details.add(threads(pool.getActiveCount(), pool.getPoolSize()));
    } else if (executor instanceof java.util.concurrent.ThreadPoolExecutor pool) {
      details.add(threads(pool.getActiveCount(), pool.getPoolSize()));
    } else if (executor instanceof ResizableExecutor pool) {
      details.add(threads(pool.getActiveCount(), pool.getPoolSize()));
    }
    return details.isEmpty() ? null : String.join(", ", details);
  }

  private static String threads(int busy, int total) {
    return String.format(Locale.ROOT, "%d/%d threads", busy, total);
  }

  // Tomcat quotes the connector names, as it also uses them in the names of its MBeans
  private static String unquote(String name) {
    return name.length() > 1 && name.startsWith("\"") && name.endsWith("\"") ? name.substring(1, name.length() - 1) : name;
  }
}
