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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests for {@link ShutdownStatusProvider}, following the shutdown of real application contexts.
 *
 * @author Joao Silva
 */
class ShutdownStatusProviderTest {

  private final Systemd systemd = mock(Systemd.class);
  // created with each context, which it reports the shutdown of
  private ShutdownStatusProvider provider;
  // the statuses sent to systemd, in order
  private final List<String> sent = new ArrayList<>();
  // the status while the web server shuts down gracefully, and while the last of the other life cycle beans stops
  private final RecordingLifecycle webServer = new RecordingLifecycle(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
  private final RecordingLifecycle lastLifecycle = new RecordingLifecycle(Integer.MIN_VALUE + 1);

  @BeforeEach
  void recordSentStatuses() {
    doAnswer(invocation -> sent.add(provider.status())).when(systemd).updateStatus();
    SystemdProcess.replace(systemd);
  }

  @AfterEach
  void tearDown() {
    SystemdProcess.reset();
  }

  /**
   * Creates a context with the provider and its stages, as the autoconfiguration does, with a life cycle bean that stops last among the other ones, and
   * with one in the phase of the graceful shutdown of the web server, if any: both record the status while they stop.
   */
  private GenericApplicationContext context(boolean webServer) {
    GenericApplicationContext context = new GenericApplicationContext();
    provider = new ShutdownStatusProvider(context);
    context.registerBean("systemdShutdownStatusProvider", ShutdownStatusProvider.class, () -> provider);
    context.registerBean("systemdShutdownRequestsStage", SmartLifecycle.class, provider::requestsStage);
    context.registerBean("systemdShutdownBeansStage", SmartLifecycle.class, provider::beansStage);
    context.registerBean("lastLifecycle", SmartLifecycle.class, () -> lastLifecycle);
    if (webServer) {
      context.registerBean("webServerGracefulShutdown", SmartLifecycle.class, () -> this.webServer);
    }
    return context;
  }

  @Test
  void reportsNothingUntilTheContextCloses() {
    try (GenericApplicationContext context = context(true)) {
      context.refresh();

      assertThat(provider.status()).isEmpty();
      assertThat(sent).isEmpty();
    }
  }

  @Test
  void reportsEachStageOfTheShutdown() {
    GenericApplicationContext context = context(true);
    context.refresh();

    context.close();

    assertThat(sent).containsExactly("Shutdown: stopping", "Shutdown: waiting for active requests", "Shutdown: destroying beans");
    // reported before the web server waits for the requests, and the beans only once every other life cycle bean stopped
    assertThat(webServer.statusWhenStopped).isEqualTo("Shutdown: waiting for active requests");
    assertThat(lastLifecycle.statusWhenStopped).isEqualTo("Shutdown: waiting for active requests");
  }

  @Test
  void skipsWaitingForRequestsWithoutWebServer() {
    GenericApplicationContext context = context(false);
    context.refresh();

    context.close();

    assertThat(sent).containsExactly("Shutdown: stopping", "Shutdown: destroying beans");
  }

  @Test
  void skipsWaitingForRequestsWhenTheWebServerShutsDownImmediately() {
    GenericApplicationContext context = context(true);
    context.setEnvironment(new MockEnvironment().withProperty("server.shutdown", "immediate"));
    context.refresh();

    context.close();

    assertThat(sent).containsExactly("Shutdown: stopping", "Shutdown: destroying beans");
  }

  @Test
  void reportsNothingWhenTheContextIsOnlyStoppedOrPaused() {
    try (GenericApplicationContext context = context(true)) {
      context.refresh();

      // as the pause endpoint of Spring Cloud, or a CRaC checkpoint, which stop the life cycle beans, such as the stages
      context.stop();
      context.start();
      context.pause();
      context.restart();

      assertThat(provider.status()).isEmpty();
      assertThat(sent).isEmpty();
    }
  }

  @Test
  void ignoresTheShutdownOfParentContexts() {
    // as the bootstrap context of Spring Cloud, the parent of the application context
    GenericApplicationContext parent = new GenericApplicationContext();
    parent.refresh();
    GenericApplicationContext context = context(true);
    context.setParent(parent);
    context.refresh();

    parent.close();

    assertThat(sent).isEmpty();
    context.close();
  }

  @Test
  void ignoresTheShutdownOfChildContexts() {
    GenericApplicationContext context = context(true);
    context.refresh();
    // as the context of the management endpoints when they use a port of their own
    AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
    child.setParent(context);
    child.refresh();

    child.close();

    assertThat(sent).isEmpty();
    context.close();
  }

  /**
   * A life cycle bean that records the status when it stops.
   */
  private final class RecordingLifecycle implements SmartLifecycle {

    private final int phase;
    private volatile boolean running;
    private volatile String statusWhenStopped;

    private RecordingLifecycle(int phase) {
      this.phase = phase;
    }

    @Override
    public void start() {
      running = true;
    }

    @Override
    public void stop() {
      running = false;
      statusWhenStopped = provider.status();
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public int getPhase() {
      return phase;
    }
  }
}
