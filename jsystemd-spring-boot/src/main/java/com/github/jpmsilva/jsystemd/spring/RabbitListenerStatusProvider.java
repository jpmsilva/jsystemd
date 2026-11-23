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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationListener;

/**
 * Provides information regarding the RabbitMQ listener containers of the application, those of Spring AMQP: as a {@link StatusProvider}, how many of
 * them run, out of those there are, and the active consumers of the {@link SimpleMessageListenerContainer} ones, such as
 * {@code RabbitMQ listeners: 2/2 running, 4 consumers}, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy
 * while any of them stopped by itself, if enabled.
 *
 * <p>The containers are those of the {@code @RabbitListener} methods, held by the {@link RabbitListenerEndpointRegistry}, and the
 * {@link MessageListenerContainer} beans, found once all singleton beans are created, so that lazy ones are not created. The active consumers drop to
 * none while the broker cannot be reached, as the containers keep running, trying to start their consumers again.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any container stopped by itself: one of its consumers failed with a fatal error, such as when its queues are missing, when it is not
 * authenticated, or when its listener throws an {@link Error}, which stops the container, and it has not been started again, which leaves the
 * application up, but no longer consuming, naming the containers in the health details, under {@code failedListenerContainers}. The containers do not
 * tell why they are stopped, so the fatal errors are learned from the {@link ListenerContainerConsumerFailedEvent} events they publish: this provider
 * must be a bean of the application context of the containers to receive them. A container that was never started, or that the application stopped, is
 * as expected. As such a container can be started again without restarting the application, the unhealthy state can be delayed, with
 * {@link #setUnhealthyDelay(java.time.Duration)}.
 *
 * <p>Not every container that stops by itself publishes such an event: a {@link org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer}
 * that gives up connecting to the broker, once its recovery back-off is exhausted, which it never is by default, does not, and is not reported as
 * unhealthy. A broker that cannot be reached does not stop the containers, which keep trying by default. That is left for the health indicators of Spring
 * Boot Actuator.
 *
 * @author Joao Silva
 */
public class RabbitListenerStatusProvider extends ListenerContainerStatusProvider<RabbitListenerEndpointRegistry, MessageListenerContainer>
    implements ApplicationListener<ListenerContainerConsumerFailedEvent> {

  // the containers that had a consumer fail with a fatal error, until they are found running again
  private final Set<Object> failures = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

  /**
   * Creates a new instance that finds the listener containers in the given bean factory.
   *
   * @param beanFactory the bean factory to find the listener endpoint registries and the listener containers in
   * @param health <code>true</code> to report the application as unhealthy while any listener container stopped by itself
   */
  public RabbitListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, boolean health) {
    super("RabbitMQ listeners", beanFactory, RabbitListenerEndpointRegistry.class, MessageListenerContainer.class, health);
  }

  /**
   * Takes note of the containers that have a consumer fail with a fatal error, which stops them.
   *
   * @param event the failure of a consumer of a listener container
   */
  @Override
  public void onApplicationEvent(ListenerContainerConsumerFailedEvent event) {
    // a consumer that is interrupted is one that takes too long to stop, in a container that is being stopped, rather than one that failed
    if (event.isFatal() && !(event.getThrowable() instanceof InterruptedException)) {
      failures.add(event.getSource());
    }
  }

  @Override
  Collection<String> ids(RabbitListenerEndpointRegistry registry) {
    return registry.getListenerContainerIds();
  }

  @Override
  @Nullable MessageListenerContainer container(RabbitListenerEndpointRegistry registry, String id) {
    return registry.getListenerContainer(id);
  }

  @Override
  boolean running(MessageListenerContainer container) {
    return container.isRunning();
  }

  @Override
  boolean failed(MessageListenerContainer container) {
    if (!failures.contains(container)) {
      return false;
    }
    if (container.isRunning()) {
      // started again since
      failures.remove(container);
      return false;
    }
    return true;
  }

  @Override
  List<String> details(Collection<MessageListenerContainer> containers) {
    // the other containers do not tell how many consumers they have
    List<SimpleMessageListenerContainer> simple = containers.stream()
        .filter(SimpleMessageListenerContainer.class::isInstance).map(SimpleMessageListenerContainer.class::cast).toList();
    if (simple.isEmpty()) {
      return List.of();
    }
    int consumers = simple.stream().mapToInt(SimpleMessageListenerContainer::getActiveConsumerCount).sum();
    return List.of(String.format(Locale.ROOT, "%d %s", consumers, consumers == 1 ? "consumer" : "consumers"));
  }
}
