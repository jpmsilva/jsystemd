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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.jpmsilva.jsystemd.RunningStatusProvider;
import com.github.jpmsilva.jsystemd.StartingStatusProvider;
import com.github.jpmsilva.jsystemd.StoppingStatusProvider;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Tests for {@link ScheduledTaskStatusProvider}, with tasks that are really scheduled, so that their outcomes are recorded by Spring itself.
 *
 * @author Joao Silva
 */
class ScheduledTaskStatusProviderTest {

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
  // schedules the tasks as Spring does for the {@code @Scheduled} methods, so that their outcomes are recorded
  private final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
  // the tasks scheduled with blocking() run until released
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void setUp() {
    scheduler.setPoolSize(4);
    scheduler.initialize();
    registrar.setTaskScheduler(scheduler);
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    scheduler.destroy();
  }

  /**
   * Registers a holder of scheduled tasks as a bean, as the one Spring sets up for the {@code @Scheduled} methods, and returns the tasks it holds.
   */
  private Set<ScheduledTask> holder(String name) {
    Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    beanFactory.registerSingleton(name, (ScheduledTaskHolder) () -> tasks);
    return tasks;
  }

  private ScheduledTaskStatusProvider provider() {
    ScheduledTaskStatusProvider provider = new ScheduledTaskStatusProvider(beanFactory);
    provider.afterSingletonsInstantiated();
    return provider;
  }

  /**
   * Schedules a task that runs right away, and then only an hour after it ends, so that the outcome of its first run is kept.
   */
  private void schedule(Set<ScheduledTask> holder, Runnable task) {
    schedule(holder, task, Duration.ofHours(1), Duration.ZERO);
  }

  private void schedule(Set<ScheduledTask> holder, Runnable task, Duration interval, Duration initialDelay) {
    holder.add(requireNonNull(registrar.scheduleFixedDelayTask(new FixedDelayTask(task, interval, initialDelay))));
  }

  private Runnable blocking() {
    return () -> {
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }

  private static Runnable failing() {
    return () -> {
      throw new IllegalStateException("failure");
    };
  }

  @Test
  void reportsTheScheduledTasksAndThoseRunningAndFailing() {
    Set<ScheduledTask> holder = holder("holder");
    ScheduledTaskStatusProvider provider = provider();
    schedule(holder, () -> { });
    schedule(holder, () -> { });
    schedule(holder, () -> { });
    schedule(holder, blocking());
    schedule(holder, failing());

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 5 (1 running; 1 failing)"));
  }

  @Test
  void reportsOnlyTheNumberOfTasksWhileNoneIsRunningNorFailing() {
    Set<ScheduledTask> holder = holder("holder");
    // not due for an hour, so it has not run yet
    schedule(holder, failing(), Duration.ofHours(1), Duration.ofHours(1));
    CountDownLatch ran = new CountDownLatch(1);
    schedule(holder, ran::countDown);

    await().until(() -> ran.getCount() == 0);

    await().untilAsserted(() -> assertThat(provider().status()).isEqualTo("Scheduled tasks: 2"));
  }

  @Test
  void reportsOnlyTheTasksRunning() {
    Set<ScheduledTask> holder = holder("holder");
    schedule(holder, blocking());
    schedule(holder, blocking());

    await().untilAsserted(() -> assertThat(provider().status()).isEqualTo("Scheduled tasks: 2 (2 running)"));
  }

  @Test
  void reportsATaskAsFailingUntilItRunsAgain() {
    Set<ScheduledTask> holder = holder("holder");
    ScheduledTaskStatusProvider provider = provider();
    CountDownLatch failed = new CountDownLatch(1);
    // fails on its first run, and runs again a second later, until released
    schedule(holder, () -> {
      if (failed.getCount() > 0) {
        failed.countDown();
        throw new IllegalStateException("failure");
      }
      blocking().run();
    }, Duration.ofSeconds(1), Duration.ZERO);

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 1 (1 failing)"));
    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 1 (1 running)"));

    release.countDown();

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 1"));
  }

  @Test
  void reportsTheTasksScheduledAfterTheHoldersAreFound() {
    Set<ScheduledTask> holder = holder("holder");
    ScheduledTaskStatusProvider provider = provider();

    assertThat(provider.status()).isEmpty();

    schedule(holder, blocking());

    await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 1 (1 running)"));
  }

  @Test
  void reportsTheTasksOfEveryHolder() {
    schedule(holder("one"), blocking());
    schedule(holder("other"), blocking());

    await().untilAsserted(() -> assertThat(provider().status()).isEqualTo("Scheduled tasks: 2 (2 running)"));
  }

  @Test
  void countsATaskHeldByTwoHoldersOnce() {
    // as a registrar that is also a bean, whose tasks the holder of Spring holds as well
    Set<ScheduledTask> holder = holder("holder");
    schedule(holder, blocking());
    beanFactory.registerSingleton("registrar", (ScheduledTaskHolder) () -> holder);

    await().untilAsserted(() -> assertThat(provider().status()).isEqualTo("Scheduled tasks: 1 (1 running)"));
  }

  @Test
  void reportsNothingWithoutScheduledTasks() {
    holder("holder");

    assertThat(provider().status()).isEmpty();
  }

  @Test
  void reportsNothingWithoutHolders() {
    assertThat(provider().status()).isEmpty();
  }

  @Test
  void reportsNothingUntilAllSingletonsAreCreated() {
    schedule(holder("holder"), blocking());

    assertThat(new ScheduledTaskStatusProvider(beanFactory).status()).isEmpty();
  }

  @Test
  void doesNotCreateLazyHolders() {
    RootBeanDefinition definition = new RootBeanDefinition(ScheduledTaskRegistrar.class);
    definition.setLazyInit(true);
    beanFactory.registerBeanDefinition("lazy", definition);

    assertThat(provider().status()).isEmpty();
    assertThat(beanFactory.containsSingleton("lazy")).isFalse();
  }

  @Test
  void onlyReportsWhileTheApplicationRuns() {
    assertThat(provider()).isInstanceOf(RunningStatusProvider.class).isNotInstanceOf(StartingStatusProvider.class)
        .isNotInstanceOf(StoppingStatusProvider.class);
  }

  @Test
  void reportsRegardlessOfDefaultLocale() {
    Set<ScheduledTask> holder = holder("holder");
    for (int i = 0; i < 10; i++) {
      schedule(holder, blocking());
    }
    ScheduledTaskStatusProvider provider = provider();
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٠" for 10
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      // the pool has 4 threads, so only 4 of the tasks run at a time
      await().untilAsserted(() -> assertThat(provider.status()).isEqualTo("Scheduled tasks: 10 (4 running)"));
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }
}
