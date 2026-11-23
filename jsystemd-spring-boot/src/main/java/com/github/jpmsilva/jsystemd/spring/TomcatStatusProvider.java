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
 * Provides information regarding the connectors of the embedded Tomcat servers of the application: as a {@link StatusProvider}, their connections and
 * threads, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while all the threads of a connector are
 * busy and requests wait for one, if enabled.
 *
 * <p>For each connector, it reports the open connections out of the maximum the connector accepts, and, when the requests are handled by a thread pool
 * rather than by virtual threads, the busy threads out of the threads in the pool, such as {@code http-nio-8080: 8/8192 connections, 3/10 threads}. A
 * connector that accepts unlimited connections only reports its threads, and one that is not started is left out.
 *
 * <p>The servers are found as they start, from their {@link WebServerInitializedEvent}: the server of the application, as well as the server of the
 * management endpoints when they use a port of their own. Nothing is reported until then.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any connector is saturated: all the threads its pool can have, the most it grows to rather than those it has so far, are busy with
 * requests, and further requests are waiting for one, naming the saturated connectors in the health details, under {@code saturatedConnectors}. A
 * connector that stays saturated usually means that its requests hang, on something that never answers, which only a restart recovers from. As a burst of
 * requests can also saturate a connector for a while, the unhealthy state should be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}: a
 * connector must then stay saturated, every time the health is evaluated, for that long.
 *
 * <p>A connector whose requests are handled by virtual threads has no thread pool to exhaust, and is never saturated.
 *
 * @author Joao Silva
 */
public class TomcatStatusProvider extends DelayableHealthProvider implements StatusProvider, ApplicationListener<WebServerInitializedEvent> {

  private final boolean health;

  // added by the event listener, read by the status updates and the health evaluations
  private final CopyOnWriteArrayList<Tomcat> servers = new CopyOnWriteArrayList<>();

  /**
   * Create a new TomcatStatusProvider, which always reports the application as healthy.
   */
  public TomcatStatusProvider() {
    this(false);
  }

  /**
   * Create a new TomcatStatusProvider.
   *
   * @param health <code>true</code> to report the application as unhealthy while all the threads of a connector are busy
   */
  public TomcatStatusProvider(boolean health) {
    this.health = health;
  }

  // for tests: whether a saturated connector reports the application as unhealthy
  boolean healthEnabled() {
    return health;
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

  /**
   * Provides the health of the application, without the delay: unhealthy while all the threads of any connector are busy and requests wait for one, if
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
    for (Tomcat server : servers) {
      for (Connector connector : server.getService().findConnectors()) {
        if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol && saturated(protocol.getExecutor())) {
          saturated.add(unquote(protocol.getName()));
        }
      }
    }
    return saturated.isEmpty() ? Health.HEALTHY : new Health(false, Map.of("saturatedConnectors", List.copyOf(saturated)));
  }

  // the same thread pools as the status; a connector that is not started has no executor, and virtual threads are not pooled, so neither is ever saturated
  private static boolean saturated(@Nullable Executor executor) {
    if (executor instanceof ThreadPoolExecutor pool) {
      return saturated(pool.getActiveCount(), pool.getMaximumPoolSize(), !pool.getQueue().isEmpty());
    } else if (executor instanceof java.util.concurrent.ThreadPoolExecutor pool) {
      return saturated(pool.getActiveCount(), pool.getMaximumPoolSize(), !pool.getQueue().isEmpty());
    } else if (executor instanceof ResizableExecutor pool) {
      // such an executor does not tell if requests are waiting, so it is saturated as soon as all its threads are busy
      return saturated(pool.getActiveCount(), pool.getMaxThreads(), true);
    }
    return false;
  }

  // out of the most threads the pool can have, as a pool that can still grow is not saturated
  private static boolean saturated(int busy, int max, boolean waiting) {
    return max > 0 && busy >= max && waiting;
  }

  // Tomcat quotes the connector names, as it also uses them in the names of its MBeans
  private static String unquote(String name) {
    return name.length() > 1 && name.startsWith("\"") && name.endsWith("\"") ? name.substring(1, name.length() - 1) : name;
  }
}
