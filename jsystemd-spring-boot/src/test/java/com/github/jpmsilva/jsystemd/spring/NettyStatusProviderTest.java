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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Tests for {@link NettyStatusProvider}, with Reactor Netty servers that really run.
 *
 * @author Joao Silva
 */
class NettyStatusProviderTest {

  private final NettyStatusProvider provider = new NettyStatusProvider();
  private final List<DisposableServer> servers = new ArrayList<>();
  private final List<Socket> sockets = new ArrayList<>();
  // the requests sent to /blocking are handled until released
  private final Sinks.Empty<Void> release = Sinks.empty();

  @AfterEach
  void tearDown() throws IOException {
    release.tryEmitEmpty();
    for (Socket socket : sockets) {
      socket.close();
    }
    for (DisposableServer server : servers) {
      server.disposeNow();
    }
  }

  /**
   * Starts a server on a free port, customized by the provider, as Spring Boot does.
   */
  private DisposableServer server() {
    return server(0);
  }

  /**
   * Starts a server on the given port, or on a free one with 0.
   */
  private DisposableServer server(int port) {
    DisposableServer server = provider.apply(HttpServer.create().port(port))
        .route(routes -> routes
            .get("/blocking", (request, response) -> release.asMono().then(response.sendString(Mono.just("released")).then()))
            .get("/", (request, response) -> response.sendString(Mono.just("ok"))))
        .bindNow();
    servers.add(server);
    return server;
  }

  /**
   * Opens a connection without sending a request on it, as an idle keep-alive connection.
   */
  private Socket connect(DisposableServer server) throws IOException {
    Socket socket = new Socket("localhost", server.port());
    sockets.add(socket);
    return socket;
  }

  private Socket request(DisposableServer server, String path) throws IOException {
    Socket socket = connect(server);
    send(socket, path);
    return socket;
  }

  private static void send(Socket socket, String path) throws IOException {
    socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(US_ASCII));
  }

  @Test
  void reportsNothingUntilServerListens() {
    provider.apply(HttpServer.create());

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsConnectionsAndRequests() throws IOException {
    DisposableServer server = server();

    // the server is reported once Reactor Netty tells that it is bound, which may happen right after it starts listening
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 0 connections, 0 requests"));

    connect(server);
    request(server, "/blocking");

    // the idle connection counts as a connection, but only the other one has a request being handled
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 2 connections, 1 requests"));

    release.tryEmitEmpty();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 2 connections, 0 requests"));
  }

  @Test
  void countsEachRequestOfAKeepAliveConnectionOnce() throws IOException {
    DisposableServer server = server();
    Socket socket = request(server, "/");
    // the first response is read, so that the second request is sent on the same connection once the first one completed
    socket.setSoTimeout(10_000);
    InputStream input = socket.getInputStream();
    StringBuilder response = new StringBuilder();
    while (!response.toString().endsWith("ok")) {
      int read = input.read();
      assertThat(read).as("response ended before its body").isNotNegative();
      response.append((char) read);
    }

    send(socket, "/blocking");

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 1 connections, 1 requests"));
  }

  @Test
  void countsDownTheConnectionsAndRequestsClosedByTheClient() throws IOException {
    DisposableServer server = server();
    Socket socket = request(server, "/blocking");
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 1 connections, 1 requests"));

    socket.close();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("netty-" + server.port() + ": 0 connections, 0 requests"));
  }

  @Test
  void reportsEachServerSortedByNameAndLeavesOutStoppedOnes() throws IOException {
    // the second server listens on a port that sorts before the one of the first, so that the order of the status is not the order in which they started
    int[] ports = freePortsSortedByStatus();
    DisposableServer first = server(ports[1]);
    String firstStatus = status(first);
    DisposableServer second = server(ports[0]);
    String secondStatus = status(second);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo(secondStatus + ", " + firstStatus));

    second.disposeNow();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo(firstStatus));
  }

  private static String status(DisposableServer server) {
    return "netty-" + server.port() + ": 0 connections, 0 requests";
  }

  /**
   * Returns two free ports, ordered by how their status sorts: both are held open while choosing them, so that they differ.
   */
  private static int[] freePortsSortedByStatus() throws IOException {
    try (ServerSocket one = new ServerSocket(0); ServerSocket other = new ServerSocket(0)) {
      String first = "netty-" + one.getLocalPort();
      String second = "netty-" + other.getLocalPort();
      return first.compareTo(second) < 0
          ? new int[] {one.getLocalPort(), other.getLocalPort()}
          : new int[] {other.getLocalPort(), one.getLocalPort()};
    }
  }
}
