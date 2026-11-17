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
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Tests for {@link TaskExecutorStatusProvider}, with task executors that really run tasks.
 *
 * @author Joao Silva
 */
class TaskExecutorStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  // the tasks sent with block() run until released
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() {
    release.countDown();
    beanFactory.destroySingletons();
  }

  private TaskExecutorStatusProvider provider() {
    TaskExecutorStatusProvider provider = new TaskExecutorStatusProvider(beanFactory);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  private void block(ThreadPoolTaskExecutor executor) {
    executor.execute(() -> {
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  private ThreadPoolTaskExecutor executor(String name) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.initialize();
    beanFactory.registerSingleton(name, executor);
    return executor;
  }

  @Test
  void reportsTheTaskExecutorsAndSchedulersSortedByName() {
    ThreadPoolTaskExecutor executor = executor("applicationTaskExecutor");
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    // named so that it sorts before the executor, although the executors are found before the schedulers
    beanFactory.registerSingleton("alertScheduler", scheduler);
    TaskExecutorStatusProvider provider = provider();

    block(executor);
    block(executor);
    block(executor);
    // the scheduler starts a thread for the task, which waits for it to be due
    scheduler.schedule(() -> { }, Instant.now().plusSeconds(3600));

    await().untilAsserted(() -> assertThat(provider.status())
        .isEqualTo("alertScheduler: 0/1 threads, 1 scheduled, applicationTaskExecutor: 2/2 threads, 1 queued"));
  }

  @Test
  void leavesOutTaskExecutorsThatAreNotInitialized() {
    beanFactory.registerSingleton("applicationTaskExecutor", new ThreadPoolTaskExecutor());

    assertThat(provider().status()).isEmpty();
  }

  @Test
  void leavesOutTaskExecutorsWithoutThreadPool() {
    // as Spring Boot sets up with spring.threads.virtual.enabled=true
    beanFactory.registerSingleton("applicationTaskExecutor", new SimpleAsyncTaskExecutor());

    assertThat(provider().status()).isEmpty();
  }

  @Test
  void doesNotCreateLazyTaskExecutors() {
    RootBeanDefinition definition = new RootBeanDefinition(ThreadPoolTaskExecutor.class);
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("lazy", definition);

    assertThat(provider().status()).isEmpty();
    assertThat(beanFactory.containsSingleton("lazy")).isFalse();
  }
}
