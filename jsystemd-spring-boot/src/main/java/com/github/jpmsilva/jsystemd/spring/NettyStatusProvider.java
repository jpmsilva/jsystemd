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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerState;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the embedded Reactor Netty servers of the application.
 *
 * <p>For each server, named after the port it listens on, it reports the open connections and the requests being handled, such as
 * {@code netty-8080: 12 connections, 3 requests}. Reactor Netty handles requests on a few event loop threads, which never block, so there is no thread
 * pool to report on. A server that is not listening is left out.
 *
 * <p>Reactor Netty does not count its connections and requests unless it records metrics with Micrometer, so this provider counts them itself: it is a
 * {@link NettyServerCustomizer}, which Spring Boot applies to the servers it creates, the server of the application as well as the server of the
 * management endpoints when they use a port of their own. Only the connections and requests of the servers created after it are counted.
 *
 * @author Joao Silva
 */
public class NettyStatusProvider implements StatusProvider, NettyServerCustomizer {

  // added once a server listens, removed once it stops, read by the status updates
  private final CopyOnWriteArrayList<ServerCounters> servers = new CopyOnWriteArrayList<>();

  /**
   * Create a new NettyStatusProvider.
   */
  public NettyStatusProvider() {
  }

  /**
   * Makes the server count its connections and requests, and report them once it listens.
   *
   * @param server the server to count the connections and requests of
   * @return the server that counts its connections and requests
   */
  @Override
  public HttpServer apply(HttpServer server) {
    ServerCounters counters = new ServerCounters();
    // these callbacks add to those already set on the server, rather than replacing them
    return server
        .doOnBound(bound -> {
          counters.port = bound.port();
          servers.addIfAbsent(counters);
        })
        .doOnUnbound(bound -> servers.remove(counters))
        .childObserve(counters);
  }

  @Override
  public String status() {
    // sorted by name, so that the order does not depend on the order in which the servers start
    Map<String, String> statuses = new TreeMap<>();
    for (ServerCounters counters : servers) {
      String name = "netty-" + counters.port;
      statuses.put(name, String.format(Locale.ROOT, "%s: %d connections, %d requests", name, counters.connections.get(), counters.requests.get()));
    }
    return String.join(", ", statuses.values());
  }

  /**
   * Counts the connections and the requests of a server, as they are observed on its connections.
   */
  private static class ServerCounters implements ConnectionObserver {

    private final AtomicInteger connections = new AtomicInteger();

    private final AtomicInteger requests = new AtomicInteger();

    // set once the server listens, read by the status updates
    private volatile int port;

    @Override
    public void onStateChange(Connection connection, State newState) {
      if (newState == State.CONNECTED) {
        connections.incrementAndGet();
        connection.channel().closeFuture().addListener(future -> connections.decrementAndGet());
      } else if (newState == HttpServerState.REQUEST_RECEIVED) {
        requests.incrementAndGet();
        // the request terminates once its response is sent, or once its connection is closed; it is only counted down once, whichever happens first
        AtomicBoolean terminated = new AtomicBoolean();
        Runnable done = () -> {
          if (terminated.compareAndSet(false, true)) {
            requests.decrementAndGet();
          }
        };
        connection.onTerminate().subscribe(null, error -> done.run(), done);
      }
    }
  }
}
