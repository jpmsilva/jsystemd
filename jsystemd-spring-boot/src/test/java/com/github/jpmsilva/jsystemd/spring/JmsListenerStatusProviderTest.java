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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.jpmsilva.jsystemd.HealthProvider;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Tests for {@link JmsListenerStatusProvider}, with listener containers that really run, on connections without a broker.
 *
 * @author Joao Silva
 */
class JmsListenerStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final JmsListenerEndpointRegistry registry = mock(JmsListenerEndpointRegistry.class);
  // the containers of the registry, by the id of their listener
  private final Map<String, MessageListenerContainer> registered = new LinkedHashMap<>();
  private final List<DefaultMessageListenerContainer> containers = new ArrayList<>();

  @BeforeEach
  void registry() {
    when(registry.getListenerContainerIds()).thenAnswer(invocation -> Set.copyOf(registered.keySet()));
    when(registry.getListenerContainer(anyString())).thenAnswer(invocation -> registered.get(invocation.<String>getArgument(0)));
    beanFactory.registerSingleton("registry", registry);
  }

  @AfterEach
  void tearDown() {
    containers.forEach(DefaultMessageListenerContainer::destroy);
  }

  /**
   * A connection factory whose connections receive no messages.
   */
  private static ConnectionFactory broker() throws JMSException {
    MessageConsumer consumer = mock(MessageConsumer.class);
    // as a consumer that waits for a message for a while
    when(consumer.receive(anyLong())).thenAnswer(invocation -> {
      Thread.sleep(20);
      return null;
    });
    Session session = mock(Session.class);
    when(session.createConsumer(any(), any())).thenReturn(consumer);
    Connection connection = mock(Connection.class);
    when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
    ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    when(connectionFactory.createConnection()).thenReturn(connection);
    return connectionFactory;
  }

  /**
   * A connection factory that cannot reach its broker.
   */
  private static ConnectionFactory unreachableBroker() throws JMSException {
    ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    when(connectionFactory.createConnection()).thenThrow(new JMSException("unreachable"));
    return connectionFactory;
  }

  private DefaultMessageListenerContainer container(ConnectionFactory connectionFactory, BackOff backOff) {
    DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setDestination(mock(Queue.class));
    container.setMessageListener((MessageListener) message -> {
    });
    container.setBackOff(backOff);
    container.afterPropertiesSet();
    containers.add(container);
    return container;
  }

  private DefaultMessageListenerContainer container(ConnectionFactory connectionFactory) {
    return container(connectionFactory, new FixedBackOff(20));
  }

  private JmsListenerStatusProvider provider(boolean health) {
    JmsListenerStatusProvider provider = new JmsListenerStatusProvider(beanFactory, health);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  @Test
  void reportsTheContainersRunning() throws JMSException {
    DefaultMessageListenerContainer orders = container(broker());
    registered.put("orders", orders);
    beanFactory.registerSingleton("paymentsContainer", container(broker()));
    JmsListenerStatusProvider provider = provider(true);

    assertThat(provider.status()).isEqualTo("JMS listeners: 0/2 running");

    orders.start();

    assertThat(provider.status()).isEqualTo("JMS listeners: 1/2 running");
  }

  @Test
  void reportsNothingWithoutContainers() {
    assertThat(provider(false).status()).isEmpty();
  }

  @Test
  void reportsTheContainersRecoveringWhichAreHealthy() throws JMSException {
    // keeps trying to connect, as by default
    DefaultMessageListenerContainer orders = container(unreachableBroker());
    registered.put("orders", orders);
    JmsListenerStatusProvider provider = provider(true);

    orders.start();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("JMS listeners: 1/1 running, 1 recovering"));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isUnhealthyWhileAContainerStoppedByItself() throws JMSException {
    // gives up connecting after an attempt
    DefaultMessageListenerContainer orders = container(unreachableBroker(), new FixedBackOff(20, 1));
    DefaultMessageListenerContainer payments = container(broker());
    registered.put("orders", orders);
    registered.put("payments", payments);
    JmsListenerStatusProvider provider = provider(true);
    payments.start();

    orders.start();

    await().untilAsserted(() -> {
      HealthProvider.Health health = provider.health();
      assertThat(health.healthy()).isFalse();
      assertThat(health.details()).containsExactly(entry("failedListenerContainers", List.of("orders")));
    });
    assertThat(provider.status()).isEqualTo("JMS listeners: 1/2 running");
  }

  @Test
  void isUnhealthyWhileAContainerRunsWithoutAnyConsumerLeft() {
    DefaultMessageListenerContainer orders = mock(DefaultMessageListenerContainer.class);
    when(orders.isRunning()).thenReturn(true);
    when(orders.getScheduledConsumerCount()).thenReturn(2);
    registered.put("orders", orders);
    JmsListenerStatusProvider provider = provider(true);
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    // none could be scheduled again
    when(orders.getPausedTaskCount()).thenReturn(2);

    assertThat(provider.health().details()).containsExactly(entry("failedListenerContainers", List.of("orders")));
  }

  @Test
  void isHealthyWhileAContainerThatDoesNotStartAutomaticallyIsStopped() throws JMSException {
    DefaultMessageListenerContainer orders = container(broker());
    orders.setAutoStartup(false);
    registered.put("orders", orders);
    JmsListenerStatusProvider provider = provider(true);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    orders.start();
    orders.stop();

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isUnhealthyWhileAContainerThatStartsAutomaticallyIsStoppedAsItCannotTellWhy() throws JMSException {
    DefaultMessageListenerContainer orders = container(broker());
    registered.put("orders", orders);
    JmsListenerStatusProvider provider = provider(true);
    orders.start();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    orders.stop();

    assertThat(provider.health().healthy()).isFalse();
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() throws JMSException {
    registered.put("orders", container(broker()));

    assertThat(provider(false).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() throws JMSException {
    registered.put("orders", container(broker()));
    JmsListenerStatusProvider provider = provider(true);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }
}
