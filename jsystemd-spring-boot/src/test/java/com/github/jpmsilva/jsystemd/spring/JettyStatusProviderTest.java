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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

/**
 * Tests for {@link JettyStatusProvider}, with Jetty servers that really run, so that the connections and threads are counted by Jetty itself.
 *
 * @author Joao Silva
 */
class JettyStatusProviderTest {

  private final JettyStatusProvider provider = new JettyStatusProvider();
  private final List<Server> servers = new ArrayList<>();
  private final List<Socket> sockets = new ArrayList<>();
  // the requests sent with request() are handled until released
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws Exception {
    release.countDown();
    for (Socket socket : sockets) {
      socket.close();
    }
    for (Server server : servers) {
      server.stop();
    }
  }

  /**
   * Starts a Jetty server with a single connector, on a free port, with a pool of 10 threads unless configured otherwise.
   */
  private Server server(Consumer<Server> configuration) throws Exception {
    QueuedThreadPool pool = new QueuedThreadPool(10, 10);
    Server server = new Server(pool);
    ServerConnector connector = new ServerConnector(server, 1, 1);
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) throws InterruptedException {
        release.await(30, TimeUnit.SECONDS);
        callback.succeeded();
        return true;
      }
    });
    configuration.accept(server);
    servers.add(server);
    server.start();
    return server;
  }

  private Server server() throws Exception {
    return server(server -> {
    });
  }

  private static int port(Server server) {
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  /**
   * Tells the provider that the server started, as Spring Boot does.
   */
  private void started(Server server) {
    started(provider, server);
  }

  private static void started(JettyStatusProvider provider, Server server) {
    JettyWebServer webServer = mock(JettyWebServer.class);
    when(webServer.getServer()).thenReturn(server);
    started(provider, webServer);
  }

  private void started(WebServer webServer) {
    started(provider, webServer);
  }

  private static void started(JettyStatusProvider provider, WebServer webServer) {
    WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
    when(event.getWebServer()).thenReturn(webServer);
    provider.onApplicationEvent(event);
  }

  /**
   * Opens a connection without sending a request on it, as an idle keep-alive connection.
   */
  private void connect(Server server) throws IOException {
    sockets.add(new Socket("localhost", port(server)));
  }

  /**
   * Sends a request that keeps a thread busy until released.
   */
  private void request(Server server) throws IOException {
    Socket socket = new Socket("localhost", port(server));
    sockets.add(socket);
    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
  }

  @Test
  void reportsNothingUntilServerStarted() throws Exception {
    server();

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsConnectionsAndThreadsRunningRequests() throws Exception {
    Server server = server();
    started(server);

    // the acceptor and selector threads are leased by Jetty, and left out
    assertThat(provider.status()).isEqualTo("jetty-" + port(server) + ": 0 connections, 0/8 threads");

    connect(server);
    connect(server);
    request(server);

    // the idle connections count as connections, but only the request keeps a thread busy
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("jetty-" + port(server) + ": 3 connections, 1/8 threads"));
  }

  @Test
  void reportsTheMaximumConnectionsWhenLimited() throws Exception {
    // as Spring Boot does with server.jetty.max-connections
    Server server = server(configured -> configured.addBean(new NetworkConnectionLimit(100, configured)));
    started(server);

    connect(server);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("jetty-" + port(server) + ": 1/100 connections, 0/8 threads"));
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void reportsOnlyConnectionsUnderVirtualThreads() throws Exception {
    // as Spring Boot does with spring.threads.virtual.enabled=true
    Server server = new Server(new VirtualThreadPool());
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);
    servers.add(server);
    server.start();
    started(server);

    assertThat(provider.status()).isEqualTo("jetty-" + port(server) + ": 0 connections");
  }

  @Test
  void reportsEachServerSortedByName() throws Exception {
    // as the server of the application and the one of the management endpoints, on a port of their own
    Server one = server();
    Server other = server();
    String oneStatus = "jetty-" + port(one) + ": 0 connections, 0/8 threads";
    String otherStatus = "jetty-" + port(other) + ": 0 connections, 0/8 threads";
    boolean oneFirst = oneStatus.compareTo(otherStatus) < 0;
    // the server that sorts last starts first, so that the order of the status is not the order in which they started
    started(oneFirst ? other : one);
    started(oneFirst ? one : other);

    assertThat(provider.status()).isEqualTo(oneFirst ? oneStatus + ", " + otherStatus : otherStatus + ", " + oneStatus);
  }

  @Test
  void ignoresOtherWebServersAndServersStartedTwice() throws Exception {
    Server server = server();
    started(mock(WebServer.class));
    started(server);
    started(server);

    assertThat(provider.status()).isEqualTo("jetty-" + port(server) + ": 0 connections, 0/8 threads");
  }

  @Test
  void leavesOutStoppedServers() throws Exception {
    Server server = server();
    started(server);

    server.stop();

    assertThat(provider.status()).isEmpty();
  }

  /**
   * Sends as many requests as the pool has threads, so that some of them wait for a thread: at most 8 of its 10 threads run requests, as Jetty keeps the
   * others to accept connections and wait for their data, and it may keep one more in reserve.
   */
  private void saturate(Server server) throws IOException {
    for (int i = 0; i < 10; i++) {
      request(server);
    }
    await().until(() -> ((QueuedThreadPool) server.getThreadPool()).getQueueSize() > 0);
  }

  @Test
  void isUnhealthyWhileAllTheThreadsOfAServerAreTakenAndRequestsWait() throws Exception {
    Server server = server();
    JettyStatusProvider provider = new JettyStatusProvider(true);
    started(provider, server);

    request(server);

    await().untilAsserted(() -> assertThat(provider.status()).endsWith("1/8 threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    saturate(server);

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("saturatedServers", List.of("jetty-" + port(server))));
    });

    release.countDown();

    await().untilAsserted(() -> assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY));
  }

  @Test
  void isHealthyWhileThePoolCanStillGrow() throws Exception {
    // a pool that starts with 4 threads, and has at most 10
    Server server = server(configured -> ((QueuedThreadPool) configured.getThreadPool()).setMinThreads(4));
    JettyStatusProvider provider = new JettyStatusProvider(true);
    started(provider, server);

    request(server);
    request(server);
    request(server);

    // more requests than the pool had threads for at first, 2 of its 4, but fewer than the 8 it can have
    await().untilAsserted(() -> assertThat(provider.status()).matches(".*, 3/\\d threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() throws Exception {
    Server server = server();
    started(server);
    saturate(server);

    // created without arguments
    assertThat(provider.healthEnabled()).isFalse();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() throws Exception {
    Server server = server();
    JettyStatusProvider provider = new JettyStatusProvider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));
    started(provider, server);
    saturate(server);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void isNeverUnhealthyUnderVirtualThreads() throws Exception {
    // there is no thread pool to exhaust: every request gets a thread of its own
    Server server = new Server(new VirtualThreadPool());
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);
    servers.add(server);
    server.start();
    JettyStatusProvider provider = new JettyStatusProvider(true);
    started(provider, server);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyOnceTheServerIsStopped() throws Exception {
    Server server = server();
    JettyStatusProvider provider = new JettyStatusProvider(true);
    started(provider, server);
    saturate(server);
    await().untilAsserted(() -> assertThat(provider.health().healthy()).isFalse());

    release.countDown();
    server.stop();

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
