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

import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

/**
 * Provides information regarding the JMS listener containers of the application, those of Spring: as a {@link StatusProvider}, how many of them run,
 * out of those there are, and how many are recovering their connection to the broker, if any, such as {@code JMS listeners: 2/2 running, 1 recovering},
 * and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while any of them stopped by itself, if enabled.
 *
 * <p>The containers are those of the {@code @JmsListener} methods, held by the {@link JmsListenerEndpointRegistry}, and the
 * {@link MessageListenerContainer} beans, found once all singleton beans are created, so that lazy ones are not created.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any container that starts automatically ({@link MessageListenerContainer#isAutoStartup()}) does not run, or, for a
 * {@link DefaultMessageListenerContainer}, runs without any consumer left, which leaves the application up, but no longer consuming, naming the containers
 * in the health details, under {@code failedListenerContainers}. A {@link DefaultMessageListenerContainer} ends up in either state by itself once its
 * back-off is exhausted, after failing to connect to the broker, or to set up its consumers, for as many attempts as it allows.
 *
 * <p>The containers do not tell why they are stopped, so a container that the application stops itself cannot be told apart from one that stopped by
 * itself, and is reported as unhealthy too, including when the application context is stopped without being closed. Only enable the health in
 * applications that leave the containers that start automatically running, or delay the unhealthy state for longer than they are stopped, with
 * {@link #setUnhealthyDelay(java.time.Duration)}. A container that does not start automatically is left for the application to start and stop, and is
 * always as expected.
 *
 * <p>By default, the back-off of a container is never exhausted: it keeps trying to connect to a broker that cannot be reached, every 5 seconds, and is
 * then recovering, rather than stopped. That is left for the health indicators of Spring Boot Actuator.
 *
 * @author Joao Silva
 */
public class JmsListenerStatusProvider extends ListenerContainerStatusProvider<JmsListenerEndpointRegistry, MessageListenerContainer> {

  /**
   * Creates a new instance that finds the listener containers in the given bean factory.
   *
   * @param beanFactory the bean factory to find the listener endpoint registries and the listener containers in
   * @param health <code>true</code> to report the application as unhealthy while any listener container stopped by itself
   */
  public JmsListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, boolean health) {
    super("JMS listeners", beanFactory, JmsListenerEndpointRegistry.class, MessageListenerContainer.class, health);
  }

  @Override
  Collection<String> ids(JmsListenerEndpointRegistry registry) {
    return registry.getListenerContainerIds();
  }

  @Override
  @Nullable MessageListenerContainer container(JmsListenerEndpointRegistry registry, String id) {
    return registry.getListenerContainer(id);
  }

  @Override
  boolean running(MessageListenerContainer container) {
    return container.isRunning();
  }

  @Override
  boolean failed(MessageListenerContainer container) {
    if (!container.isRunning()) {
      return container.isAutoStartup();
    }
    // the consumers that are scheduled but paused are those that could not be scheduled again, as while the container is stopped
    return container instanceof DefaultMessageListenerContainer consumers
        && consumers.getScheduledConsumerCount() - consumers.getPausedTaskCount() < 1;
  }

  @Override
  List<String> details(Collection<MessageListenerContainer> containers) {
    long recovering = containers.stream()
        .filter(container -> container instanceof DefaultMessageListenerContainer recoverable && recoverable.isRecovering()).count();
    return recovering == 0 ? List.of() : List.of(String.format(Locale.ROOT, "%d recovering", recovering));
  }
}
