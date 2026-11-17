/*
 * Copyright 2023-2026 Joao Silva
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

import com.github.jpmsilva.jsystemd.Systemd;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Sends the stopping signal to systemd when the application context starts closing, before its life cycle beans stop, such as the embedded web server,
 * which then waits for the active requests.
 *
 * <p>Stopping the context without closing it does not send it, as when the context is stopped or paused and started again afterwards, such as by the pause
 * endpoint of Spring Cloud, or for a CRaC checkpoint: the application keeps running, and systemd keeps enforcing the watchdog.
 *
 * <p>Register it as a bean of the application context it is given, for that context to publish its events to it.
 *
 * @author Joao Silva
 * @see Systemd#stopping()
 */
public class SystemdLifecycle implements ApplicationListener<ContextClosedEvent> {

  private final ApplicationContext context;

  /**
   * Creates a new instance, which sends the stopping signal with {@link Systemd#instance()} when the given context starts closing.
   *
   * @param context the application context of the application, which this instance must be a bean of, rather than a parent or child context
   */
  public SystemdLifecycle(ApplicationContext context) {
    this.context = requireNonNull(context, "Application context must not be null");
  }

  /**
   * Sends the stopping signal, when the event is about its own application context: the events of child contexts, such as the one of the management
   * endpoints when they use a port of their own, are also published to their parents.
   *
   * <p>Spring publishes the event first when closing a context, before it stops the life cycle beans and destroys the beans, despite the wording of the
   * documentation of {@link ContextClosedEvent}: the application is still running when the event is received.
   *
   * @param event the event of a context that started closing
   */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    if (event.getApplicationContext() == context) {
      Systemd.instance().stopping();
    }
  }
}
