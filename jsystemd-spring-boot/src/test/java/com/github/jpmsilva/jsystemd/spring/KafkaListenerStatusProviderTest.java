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
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;

/**
 * Tests for {@link KafkaListenerStatusProvider}, with listener containers that really run, on consumers without a broker.
 *
 * @author Joao Silva
 */
class KafkaListenerStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
  // the containers of the registry, by the id of their listener
  private final Map<String, MessageListenerContainer> registered = new LinkedHashMap<>();
  private final List<MessageListenerContainer> containers = new ArrayList<>();

  @BeforeEach
  void registry() {
    when(registry.getListenerContainerIds()).thenAnswer(invocation -> Set.copyOf(registered.keySet()));
    when(registry.getListenerContainer(anyString())).thenAnswer(invocation -> registered.get(invocation.<String>getArgument(0)));
    beanFactory.registerSingleton("registry", registry);
  }

  @AfterEach
  void tearDown() {
    containers.forEach(MessageListenerContainer::stop);
  }

  /**
   * A consumer without a broker, which polls no records.
   */
  private static MockConsumer<String, String> consumer() {
    return new MockConsumer<>("earliest");
  }

  /**
   * Creates a container that is assigned the given number of partitions of a topic of its own, on the given consumer.
   */
  private ConcurrentMessageListenerContainer<String, String> container(String name, int partitions, MockConsumer<String, String> consumer) {
    List<TopicPartition> assigned = IntStream.range(0, partitions).mapToObj(partition -> new TopicPartition(name, partition)).toList();
    Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
    assigned.forEach(partition -> offsets.put(partition, 0L));
    consumer.updateBeginningOffsets(offsets);
    ContainerProperties properties = new ContainerProperties(assigned.stream()
        .map(partition -> new TopicPartitionOffset(partition.topic(), partition.partition())).toArray(TopicPartitionOffset[]::new));
    properties.setGroupId("group");
    properties.setMessageListener((MessageListener<String, String>) data -> {
    });
    // the consumer polls without waiting, as it has no broker to wait for
    properties.setIdleBetweenPolls(20);
    DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(
        Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"), new StringDeserializer(), new StringDeserializer()) {
      @Override
      protected Consumer<String, String> createKafkaConsumer(Map<String, Object> configProps) {
        return consumer;
      }
    };
    ConcurrentMessageListenerContainer<String, String> container = new ConcurrentMessageListenerContainer<>(factory, properties);
    container.setBeanName(name);
    containers.add(container);
    return container;
  }

  private KafkaListenerStatusProvider provider(boolean health) {
    KafkaListenerStatusProvider provider = new KafkaListenerStatusProvider(beanFactory, health);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  @Test
  void reportsTheContainersRunningAndTheirPartitions() {
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 2, consumer());
    ConcurrentMessageListenerContainer<String, String> payments = container("payments", 1, consumer());
    registered.put("orders", orders);
    registered.put("payments", payments);
    KafkaListenerStatusProvider provider = provider(false);

    orders.start();

    assertThat(provider.status()).isEqualTo("Kafka listeners: 1/2 running, 2 partitions");

    payments.start();

    assertThat(provider.status()).isEqualTo("Kafka listeners: 2/2 running, 3 partitions");
  }

  @Test
  void reportsThePausedContainers() {
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 1, consumer());
    registered.put("orders", orders);
    KafkaListenerStatusProvider provider = provider(false);
    orders.start();

    orders.pause();

    // paused by the consumer thread, on its next poll
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Kafka listeners: 1/1 running, 1 partition, 1 paused"));
  }

  @Test
  void reportsNothingWithoutContainers() {
    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void reportsTheContainersThatAreBeansAndThoseRegisteredLater() {
    ConcurrentMessageListenerContainer<String, String> bean = container("ordersContainer", 1, consumer());
    beanFactory.registerSingleton("ordersContainer", bean);
    KafkaListenerStatusProvider provider = provider(false);
    bean.start();

    assertThat(provider.status()).isEqualTo("Kafka listeners: 1/1 running, 1 partition");

    // as when a listener is registered while the application runs
    registered.put("payments", container("payments", 1, consumer()));

    assertThat(provider.status()).isEqualTo("Kafka listeners: 1/2 running, 1 partition");
  }

  @Test
  void isUnhealthyWhileAContainerStoppedByItself() {
    MockConsumer<String, String> consumer = consumer();
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 1, consumer);
    ConcurrentMessageListenerContainer<String, String> payments = container("payments", 1, consumer());
    registered.put("orders", orders);
    registered.put("payments", payments);
    KafkaListenerStatusProvider provider = provider(true);
    orders.start();
    payments.start();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    // not authorized, which the container does not recover from
    consumer.setPollException(new TopicAuthorizationException("orders"));

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("failedListenerContainers", List.of("orders")));
    });
    assertThat(provider.status()).startsWith("Kafka listeners: 1/2 running");
  }

  @Test
  void isHealthyWhileAContainerWasNeverStartedOrWasStoppedOrPaused() {
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 1, consumer());
    ConcurrentMessageListenerContainer<String, String> payments = container("payments", 1, consumer());
    registered.put("orders", orders);
    registered.put("payments", payments);
    registered.put("refunds", container("refunds", 1, consumer()));
    KafkaListenerStatusProvider provider = provider(true);
    orders.start();
    payments.start();

    orders.stop();
    payments.pause();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Kafka listeners: 1/3 running, 1 partition, 1 paused"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    MockConsumer<String, String> consumer = consumer();
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 1, consumer);
    registered.put("orders", orders);
    KafkaListenerStatusProvider provider = provider(false);
    orders.start();

    consumer.setPollException(new TopicAuthorizationException("orders"));

    await().untilAsserted(() -> assertThat(orders.isInExpectedState()).isFalse());
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    MockConsumer<String, String> consumer = consumer();
    ConcurrentMessageListenerContainer<String, String> orders = container("orders", 1, consumer);
    registered.put("orders", orders);
    KafkaListenerStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));
    orders.start();

    consumer.setPollException(new TopicAuthorizationException("orders"));

    await().untilAsserted(() -> assertThat(orders.isInExpectedState()).isFalse());
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
