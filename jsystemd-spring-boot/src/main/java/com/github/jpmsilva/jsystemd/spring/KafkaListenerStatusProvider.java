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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Provides information regarding the Kafka listener containers of the application, those of Spring for Apache Kafka: as a {@link StatusProvider}, how
 * many of them run, out of those there are, the partitions assigned to those that run, and how many are paused, if any, such as
 * {@code Kafka listeners: 3/3 running, 12 partitions, 1 paused}, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as
 * unhealthy while any of them stopped by itself, if enabled.
 *
 * <p>The containers are those of the {@code @KafkaListener} methods, held by the {@link KafkaListenerEndpointRegistry}, and the
 * {@link MessageListenerContainer} beans, found once all singleton beans are created, so that lazy ones are not created. Only what the containers already
 * know is reported, without asking the brokers, so that the lag of the consumers is not.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while any container is not in its expected state, as told by {@link MessageListenerContainer#isInExpectedState()}: it stopped by itself, after
 * an error it does not recover from, such as when it is not authorized, when it is fenced, or when its error handler stops it, which leaves the
 * application up, but no longer consuming, naming the containers in the health details, under {@code failedListenerContainers}. A container that was never
 * started, or that the application stopped or paused, is as expected. As such a container can be started again without restarting the application, the
 * unhealthy state can be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}.
 *
 * <p>Brokers that cannot be reached do not stop the containers, which keep trying. That is left for the health indicators of Spring Boot Actuator.
 *
 * @author Joao Silva
 */
public class KafkaListenerStatusProvider extends ListenerContainerStatusProvider<KafkaListenerEndpointRegistry, MessageListenerContainer> {

  /**
   * Creates a new instance that finds the listener containers in the given bean factory.
   *
   * @param beanFactory the bean factory to find the listener endpoint registries and the listener containers in
   * @param health <code>true</code> to report the application as unhealthy while any listener container stopped by itself
   */
  public KafkaListenerStatusProvider(ConfigurableListableBeanFactory beanFactory, boolean health) {
    super("Kafka listeners", beanFactory, KafkaListenerEndpointRegistry.class, MessageListenerContainer.class, health);
  }

  @Override
  Collection<String> ids(KafkaListenerEndpointRegistry registry) {
    return registry.getListenerContainerIds();
  }

  @Override
  @Nullable MessageListenerContainer container(KafkaListenerEndpointRegistry registry, String id) {
    return registry.getListenerContainer(id);
  }

  @Override
  boolean running(MessageListenerContainer container) {
    return container.isRunning();
  }

  @Override
  boolean failed(MessageListenerContainer container) {
    return !container.isInExpectedState();
  }

  @Override
  List<String> details(Collection<MessageListenerContainer> containers) {
    int partitions = 0;
    boolean assignable = false;
    int paused = 0;
    for (MessageListenerContainer container : containers) {
      int assigned = partitions(container);
      if (assigned >= 0) {
        assignable = true;
        partitions += assigned;
      }
      if (paused(container)) {
        paused++;
      }
    }
    List<String> details = new ArrayList<>();
    if (assignable) {
      details.add(String.format(Locale.ROOT, "%d %s", partitions, partitions == 1 ? "partition" : "partitions"));
    }
    if (paused > 0) {
      details.add(String.format(Locale.ROOT, "%d paused", paused));
    }
    return details;
  }

  /**
   * Returns the number of partitions assigned to the given container, or -1 when it does not tell, such as the containers of share groups, which are
   * not assigned any.
   */
  private static int partitions(MessageListenerContainer container) {
    try {
      // null until partitions are assigned, and a container that is stopped still tells the ones it had
      Collection<TopicPartition> assigned = container.getAssignedPartitions();
      return assigned == null || !container.isRunning() ? 0 : assigned.size();
    } catch (UnsupportedOperationException e) {
      return -1;
    }
  }

  /**
   * Tells if the given container is paused, which only a running container is, when it can be paused at all.
   */
  private static boolean paused(MessageListenerContainer container) {
    try {
      return container.isRunning() && container.isContainerPaused();
    } catch (UnsupportedOperationException e) {
      return false;
    }
  }
}
