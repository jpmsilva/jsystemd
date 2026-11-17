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

import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Implementation of {@link StatusProvider} that provides the stage the application is at while it shuts down, such as
 * {@code Shutdown: waiting for active requests}, so that a slow shutdown can be told apart from a hung one with {@code systemctl status}.
 *
 * <p>The stages follow the shutdown of the application context:
 * <ol>
 *   <li>{@code Shutdown: stopping}, once the context starts closing, while its life cycle beans stop, such as message listeners;</li>
 *   <li>{@code Shutdown: waiting for active requests}, while the embedded web server waits for the requests being handled to complete, when it shuts
 *   down gracefully, as it does by default;</li>
 *   <li>{@code Shutdown: destroying beans}, once all life cycle beans are stopped, while the beans are destroyed, such as connection pools.</li>
 * </ol>
 *
 * <p>Every change of stage is sent to systemd right away. The status of the other providers keeps being sent while the application shuts down, such as
 * the connections of the web server, which drop as the requests complete. Nothing is reported until the application starts shutting down, nor when the
 * context is stopped without being closed, as when it is paused, or for a CRaC checkpoint.
 *
 * <p>The stages are followed by life cycle beans, which the autoconfiguration registers along with this provider.
 *
 * @author Joao Silva
 */
public class ShutdownStatusProvider implements StatusProvider, ApplicationListener<ContextClosedEvent> {

  /**
   * The name of the bean of the web server that shuts it down gracefully, registered by Spring Boot when it starts an embedded web server.
   */
  private static final String GRACEFUL_SHUTDOWN_BEAN_NAME = "webServerGracefulShutdown";

  private final PhaseStatusProvider phase;

  private final ApplicationContext context;

  // whether the context is closing, as its life cycle beans, such as the stages, also stop when it is only stopped, as when paused
  private volatile boolean closing;

  /**
   * Creates a new instance, which sends the status to systemd on every change of stage.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   *
   * @param context the application context of the application, which this instance must be a bean of, rather than a parent or child context
   */
  public ShutdownStatusProvider(ApplicationContext context) {
    this.context = requireNonNull(context, "Application context must not be null");
    this.phase = new PhaseStatusProvider(true);
  }

  /**
   * Reports that the application started shutting down, when the event is about its own application context: the events of child contexts, such as the
   * one of the management endpoints when they use a port of their own, are also published to their parents.
   *
   * <p>Spring publishes the event first when closing a context, before it stops the life cycle beans and destroys the beans, despite the wording of the
   * documentation of {@link ContextClosedEvent}: the application is still running when the event is received.
   *
   * @param event the event of a context that started closing
   */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    if (event.getApplicationContext() == context) {
      closing = true;
      phase.setPhase("Shutdown: stopping");
    }
  }

  /**
   * Returns the life cycle bean that reports when the web server starts waiting for the active requests: it is stopped right before the web server shuts
   * down gracefully. Register it as a bean, for the context to stop it.
   *
   * @return the life cycle bean of the stage of waiting for the active requests
   */
  public SmartLifecycle requestsStage() {
    return new Stage(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1, () -> {
      if (!closing) {
        return;
      }
      // the embedded web server only waits for the active requests when it shuts down gracefully, as it does by default
      if (context.containsBean(GRACEFUL_SHUTDOWN_BEAN_NAME)
          && !"immediate".equalsIgnoreCase(context.getEnvironment().getProperty("server.shutdown"))) {
        phase.setPhase("Shutdown: waiting for active requests");
      }
    });
  }

  /**
   * Returns the life cycle bean that reports when the beans start being destroyed: it is stopped after every other life cycle bean, after which the
   * context destroys its beans. Register it as a bean, for the context to stop it.
   *
   * @return the life cycle bean of the stage of destroying the beans
   */
  public SmartLifecycle beansStage() {
    return new Stage(Integer.MIN_VALUE, () -> {
      if (closing) {
        phase.setPhase("Shutdown: destroying beans");
      }
    });
  }

  @Override
  public String status() {
    return phase.status();
  }

  /**
   * A life cycle bean that reports a stage of the shutdown when stopped.
   */
  private static final class Stage implements SmartLifecycle {

    private final int phase;

    private final Runnable onStop;

    // read and written by the startup and shutdown threads
    private volatile boolean running;

    private Stage(int phase, Runnable onStop) {
      this.phase = phase;
      this.onStop = onStop;
    }

    @Override
    public void start() {
      running = true;
    }

    @Override
    public void stop() {
      running = false;
      onStop.run();
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
