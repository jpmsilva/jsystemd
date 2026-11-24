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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

/**
 * Tests for {@link TomcatStatusProvider}, with Tomcat servers that really run, so that the connections and threads are counted by Tomcat itself.
 *
 * @author Joao Silva
 */
class TomcatStatusProviderTest {

  @TempDir
  private Path baseDir;

  private final TomcatStatusProvider provider = new TomcatStatusProvider();
  private final List<Tomcat> servers = new ArrayList<>();
  private final List<Socket> sockets = new ArrayList<>();
  // the requests sent with request() are handled until released
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws IOException, LifecycleException {
    release.countDown();
    for (Socket socket : sockets) {
      socket.close();
    }
    for (Tomcat server : servers) {
      server.stop();
      server.destroy();
    }
  }

  /**
   * Starts a Tomcat server with a single connector, on a free port, so that the connector is named {@code http-nio-<port>}.
   */
  private Tomcat server(Consumer<AbstractProtocol<?>> configuration) throws IOException, LifecycleException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Tomcat server = new Tomcat();
    server.setBaseDir(baseDir.resolve(String.valueOf(port)).toString());
    server.setPort(port);
    configuration.accept((AbstractProtocol<?>) server.getConnector().getProtocolHandler());
    Context context = server.addContext("", null);
    Tomcat.addServlet(context, "blocking", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        response.getWriter().write("released");
      }
    }).addMapping("/");
    servers.add(server);
    server.start();
    return server;
  }

  private Tomcat server() throws IOException, LifecycleException {
    return server(protocol -> {
    });
  }

  private static int port(Tomcat server) {
    return server.getConnector().getLocalPort();
  }

  /**
   * Tells the provider that the server started, as Spring Boot does.
   */
  private void started(Tomcat server) {
    started(provider, server);
  }

  private static void started(TomcatStatusProvider provider, Tomcat server) {
    TomcatWebServer webServer = mock(TomcatWebServer.class);
    when(webServer.getTomcat()).thenReturn(server);
    started(provider, webServer);
  }

  private void started(WebServer webServer) {
    started(provider, webServer);
  }

  private static void started(TomcatStatusProvider provider, WebServer webServer) {
    WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
    when(event.getWebServer()).thenReturn(webServer);
    provider.onApplicationEvent(event);
  }

  /**
   * Waits for the status of a server that handles no request, as a thread of its pool can be briefly busy right after it starts, though no request was
   * sent to it.
   */
  private void awaitStatus(String status) {
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo(status));
  }

  /**
   * Sends more requests than the pool of a small server has threads for, so that one of them waits for a thread.
   */
  private void saturate(Tomcat server) throws IOException {
    for (int i = 0; i < 3; i++) {
      request(server);
    }
  }

  /**
   * Starts a Tomcat server whose pool starts with a single thread, and has at most 2.
   */
  private Tomcat smallServer() throws IOException, LifecycleException {
    return server(protocol -> {
      protocol.setMinSpareThreads(1);
      protocol.setMaxThreads(2);
    });
  }

  /**
   * Opens a connection without sending a request on it, as an idle keep-alive connection.
   */
  private void connect(Tomcat server) throws IOException {
    sockets.add(new Socket("localhost", port(server)));
  }

  /**
   * Sends a request that keeps a thread busy until released.
   */
  private void request(Tomcat server) throws IOException {
    Socket socket = new Socket("localhost", port(server));
    sockets.add(socket);
    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
  }

  @Test
  void reportsNothingUntilServerStarted() throws IOException, LifecycleException {
    server();

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsConnectionsAndThreads() throws IOException, LifecycleException {
    Tomcat server = server();
    started(server);

    // a fresh server has no connections, and the pool starts with 10 threads, the default minimum of spare threads
    awaitStatus("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");

    connect(server);
    connect(server);
    request(server);

    // the idle connections count as connections, but only the request keeps a thread busy
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 3/8192 connections, 1/10 threads"));
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void reportsOnlyConnectionsUnderVirtualThreads() throws IOException, LifecycleException {
    // as Spring Boot does with spring.threads.virtual.enabled=true
    Tomcat server = server(protocol -> protocol.setExecutor(new VirtualThreadExecutor("tomcat-handler-")));
    started(server);

    connect(server);
    request(server);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 2/8192 connections"));
  }

  @Test
  void reportsOnlyThreadsWithUnlimitedConnections() throws IOException, LifecycleException {
    Tomcat server = server(protocol -> protocol.setMaxConnections(-1));
    started(server);

    awaitStatus("http-nio-" + port(server) + ": 0/10 threads");
  }

  @Test
  void reportsTheConnectorsOfEachServerSortedByName() throws IOException, LifecycleException {
    // as the server of the application and the one of the management endpoints, on a port of their own
    Tomcat application = server(protocol -> protocol.setMaxConnections(100));
    Tomcat management = server(protocol -> protocol.setMaxConnections(10));
    String applicationStatus = "http-nio-" + port(application) + ": 0/100 connections, 0/10 threads";
    String managementStatus = "http-nio-" + port(management) + ": 0/10 connections, 0/10 threads";
    boolean applicationFirst = applicationStatus.compareTo(managementStatus) < 0;
    // the server that sorts last starts first, so that the order of the status is not the order in which they started
    started(applicationFirst ? management : application);
    started(applicationFirst ? application : management);

    awaitStatus(applicationFirst ? applicationStatus + ", " + managementStatus : managementStatus + ", " + applicationStatus);
  }

  @Test
  void reportsServerOnceWhenStartedAgain() throws IOException, LifecycleException {
    Tomcat server = server();
    started(server);
    started(server);

    awaitStatus("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");
  }

  @Test
  void leavesOutStoppedServer() throws IOException, LifecycleException {
    Tomcat server = server();
    started(server);

    server.stop();

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void ignoresOtherWebServers() {
    started(mock(WebServer.class));

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsRegardlessOfDefaultLocale() throws IOException, LifecycleException {
    Tomcat server = server();
    started(server);
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٠" for 10
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      awaitStatus("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void isUnhealthyWhileAllTheThreadsOfAConnectorAreBusyAndRequestsWait() throws IOException, LifecycleException {
    Tomcat server = smallServer();
    TomcatStatusProvider provider = new TomcatStatusProvider(true);
    started(provider, server);

    request(server);

    // the single thread of the pool is busy, but the pool can still grow
    await().untilAsserted(() -> assertThat(provider.status()).endsWith("1/1 threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    request(server);

    // all the threads the pool can have are busy, but no request waits for one
    await().untilAsserted(() -> assertThat(provider.status()).endsWith("2/2 threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    request(server);

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("saturatedConnectors", List.of("http-nio-" + port(server))));
    });

    release.countDown();

    await().untilAsserted(() -> assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY));
  }

  @Test
  void namesOnlyTheSaturatedConnectors() throws IOException, LifecycleException {
    // as the server of the application and the one of the management endpoints, on a port of their own
    Tomcat application = smallServer();
    Tomcat management = smallServer();
    TomcatStatusProvider provider = new TomcatStatusProvider(true);
    started(provider, application);
    started(provider, management);
    saturate(application);
    request(management);
    request(management);

    await().untilAsserted(() -> assertThat(provider.health().details())
        .containsExactly(entry("saturatedConnectors", List.of("http-nio-" + port(application)))));
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() throws IOException, LifecycleException {
    Tomcat server = smallServer();
    started(server);
    saturate(server);

    // created without arguments
    assertThat(provider.healthEnabled()).isFalse();
    await().untilAsserted(() -> assertThat(provider.status()).endsWith("3/8192 connections, 2/2 threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() throws IOException, LifecycleException {
    Tomcat server = smallServer();
    TomcatStatusProvider provider = new TomcatStatusProvider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));
    started(provider, server);
    saturate(server);

    await().untilAsserted(() -> assertThat(provider.status()).endsWith("3/8192 connections, 2/2 threads"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void isNeverUnhealthyUnderVirtualThreads() throws IOException, LifecycleException {
    // there is no thread pool to exhaust: every request gets a thread of its own
    Tomcat server = server(protocol -> protocol.setExecutor(new VirtualThreadExecutor("tomcat-handler-")));
    TomcatStatusProvider provider = new TomcatStatusProvider(true);
    started(provider, server);
    request(server);
    request(server);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 2/8192 connections"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyOnceTheServerIsStopped() throws IOException, LifecycleException {
    Tomcat server = smallServer();
    TomcatStatusProvider provider = new TomcatStatusProvider(true);
    started(provider, server);
    saturate(server);
    await().untilAsserted(() -> assertThat(provider.health().healthy()).isFalse());

    release.countDown();
    server.stop();

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
