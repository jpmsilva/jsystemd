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

package com.github.jpmsilva.jsystemd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ExecutorStatusProvider}, with thread pools that really run tasks.
 *
 * @author Joao Silva
 */
class ExecutorStatusProviderTest {

  private final List<ExecutorService> executors = new ArrayList<>();
  // the tasks sent with block() run until released
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws InterruptedException {
    release.countDown();
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private <T extends ExecutorService> T track(T executor) {
    executors.add(executor);
    return executor;
  }

  private void block(ExecutorService executor, CountDownLatch started) {
    executor.execute(() -> {
      started.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  @Test
  void reportsTheBusyThreadsAndTheQueuedTasks() throws InterruptedException {
    ThreadPoolExecutor pool = track(new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
    CountDownLatch started = new CountDownLatch(2);

    block(pool, started);
    block(pool, started);
    block(pool, new CountDownLatch(1));
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

    // both threads run a task, and the third task waits for one of them
    assertThat(new ExecutorStatusProvider("workers", pool).status()).isEqualTo("workers: 2/2 threads, 1 queued");
  }

  @Test
  void reportsTheScheduledTasksOfAScheduledThreadPool() throws InterruptedException {
    ScheduledThreadPoolExecutor pool = track(new ScheduledThreadPoolExecutor(1));
    CountDownLatch started = new CountDownLatch(1);

    block(pool, started);
    pool.schedule(() -> { }, 1, TimeUnit.HOURS);
    pool.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.HOURS);
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(new ExecutorStatusProvider("scheduler", pool).status()).isEqualTo("scheduler: 1/1 threads, 2 scheduled");
  }

  @Test
  void reportsNothingWhileThereIsNoThreadPool() {
    AtomicReference<ThreadPoolExecutor> pool = new AtomicReference<>();
    ExecutorStatusProvider provider = new ExecutorStatusProvider("workers", pool::get);

    assertThat(provider.status()).isEmpty();

    pool.set(track(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>())));

    assertThat(provider.status()).isEqualTo("workers: 0/0 threads, 0 queued");
  }
}
