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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
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
    TomcatWebServer webServer = mock(TomcatWebServer.class);
    when(webServer.getTomcat()).thenReturn(server);
    started(webServer);
  }

  private void started(WebServer webServer) {
    WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
    when(event.getWebServer()).thenReturn(webServer);
    provider.onApplicationEvent(event);
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
    assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");

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

    assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 0/10 threads");
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

    assertThat(provider.status())
        .isEqualTo(applicationFirst ? applicationStatus + ", " + managementStatus : managementStatus + ", " + applicationStatus);
  }

  @Test
  void reportsServerOnceWhenStartedAgain() throws IOException, LifecycleException {
    Tomcat server = server();
    started(server);
    started(server);

    assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");
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
      assertThat(provider.status()).isEqualTo("http-nio-" + port(server) + ": 0/8192 connections, 0/10 threads");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }
}
