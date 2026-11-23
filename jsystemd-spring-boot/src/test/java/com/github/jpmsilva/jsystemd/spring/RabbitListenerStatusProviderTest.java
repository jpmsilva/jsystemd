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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Tests for {@link RabbitListenerStatusProvider}, with a listener container that really stops by itself, as it is not authenticated, and with others that
 * would need a broker to run.
 *
 * @author Joao Silva
 */
class RabbitListenerStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
  // the containers of the registry, by the id of their listener
  private final Map<String, MessageListenerContainer> registered = new LinkedHashMap<>();

  @BeforeEach
  void registry() {
    when(registry.getListenerContainerIds()).thenAnswer(invocation -> Set.copyOf(registered.keySet()));
    when(registry.getListenerContainer(anyString())).thenAnswer(invocation -> registered.get(invocation.<String>getArgument(0)));
    beanFactory.registerSingleton("registry", registry);
  }

  private static SimpleMessageListenerContainer simple(boolean running, int consumers) {
    SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    when(container.isRunning()).thenReturn(running);
    when(container.getActiveConsumerCount()).thenReturn(consumers);
    return container;
  }

  private static DirectMessageListenerContainer direct(boolean running) {
    DirectMessageListenerContainer container = mock(DirectMessageListenerContainer.class);
    when(container.isRunning()).thenReturn(running);
    return container;
  }

  private static ListenerContainerConsumerFailedEvent failure(MessageListenerContainer container, Throwable throwable, boolean fatal) {
    return new ListenerContainerConsumerFailedEvent(container, "failed", throwable, fatal);
  }

  private RabbitListenerStatusProvider provider(boolean health) {
    RabbitListenerStatusProvider provider = new RabbitListenerStatusProvider(beanFactory, health);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  @Test
  void reportsTheContainersRunningAndTheConsumersOfTheSimpleOnes() {
    registered.put("orders", simple(true, 3));
    registered.put("payments", simple(false, 0));
    registered.put("refunds", direct(true));
    beanFactory.registerSingleton("auditContainer", simple(true, 1));

    assertThat(provider(false).status()).isEqualTo("RabbitMQ listeners: 3/4 running, 4 consumers");
  }

  @Test
  void reportsNoConsumersWithoutSimpleContainers() {
    registered.put("orders", direct(true));

    assertThat(provider(false).status()).isEqualTo("RabbitMQ listeners: 1/1 running");
  }

  @Test
  void reportsNothingWithoutContainers() {
    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void isUnhealthyWhileAContainerStoppedByItself() {
    RabbitListenerStatusProvider provider = provider(true);
    // not authenticated, which the container does not recover from
    ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    when(connectionFactory.createConnection()).thenThrow(new AmqpAuthenticationException(new IllegalStateException("denied")));
    SimpleMessageListenerContainer orders = new SimpleMessageListenerContainer(connectionFactory);
    orders.setQueueNames("orders");
    orders.setMessageListener((MessageListener) message -> {
    });
    // as the application context does, of which the provider is a listener
    orders.setApplicationEventPublisher(event -> {
      if (event instanceof ListenerContainerConsumerFailedEvent failed) {
        provider.onApplicationEvent(failed);
      }
    });
    registered.put("orders", orders);
    registered.put("payments", simple(true, 1));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    assertThatThrownBy(orders::start).isInstanceOf(AmqpException.class);

    // the container stops by itself, and only then tells why
    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("failedListenerContainers", List.of("orders")));
    });
    assertThat(orders.isRunning()).isFalse();
    assertThat(provider.status()).isEqualTo("RabbitMQ listeners: 1/2 running, 1 consumer");
  }

  @Test
  void isHealthyAgainOnceTheContainerIsStartedAgain() {
    SimpleMessageListenerContainer orders = simple(false, 0);
    registered.put("orders", orders);
    RabbitListenerStatusProvider provider = provider(true);
    provider.onApplicationEvent(failure(orders, new IllegalStateException("fatal"), true));
    assertThat(provider.health().healthy()).isFalse();

    when(orders.isRunning()).thenReturn(true);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    // stopped by the application, after it was started again
    when(orders.isRunning()).thenReturn(false);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyWhileAContainerWasNeverStartedOrWasStopped() {
    registered.put("orders", simple(false, 0));
    registered.put("payments", direct(false));

    assertThat(provider(true).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyWhileTheConsumersOfAContainerFailButAreStartedAgain() {
    SimpleMessageListenerContainer orders = simple(false, 0);
    registered.put("orders", orders);
    RabbitListenerStatusProvider provider = provider(true);

    // as when the broker cannot be reached, which is not fatal
    provider.onApplicationEvent(failure(orders, new IllegalStateException("not fatal"), false));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyWhileAContainerInterruptsItsConsumersToStop() {
    SimpleMessageListenerContainer orders = simple(false, 0);
    registered.put("orders", orders);
    RabbitListenerStatusProvider provider = provider(true);

    provider.onApplicationEvent(failure(orders, new InterruptedException("stopping"), true));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    SimpleMessageListenerContainer orders = simple(false, 0);
    registered.put("orders", orders);
    RabbitListenerStatusProvider provider = provider(false);

    provider.onApplicationEvent(failure(orders, new IllegalStateException("fatal"), true));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    SimpleMessageListenerContainer orders = simple(false, 0);
    registered.put("orders", orders);
    RabbitListenerStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    provider.onApplicationEvent(failure(orders, new IllegalStateException("fatal"), true));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
