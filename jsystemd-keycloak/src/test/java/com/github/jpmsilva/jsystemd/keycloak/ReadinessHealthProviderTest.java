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

package com.github.jpmsilva.jsystemd.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.jpmsilva.jsystemd.HealthProvider.Health;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReadinessHealthProvider}.
 *
 * @author Joao Silva
 */
class ReadinessHealthProviderTest {

  // the outcome of the readiness health checks: null while they are all up, as they are by default
  private final AtomicReference<@Nullable List<String>> down = new AtomicReference<>();
  // how many times the checks ran
  private final AtomicInteger runs = new AtomicInteger();
  // released for the checks to complete, which they do right away by default
  private final CountDownLatch completing = new CountDownLatch(1);
  private final ReadinessHealthProvider provider = new ReadinessHealthProvider(this::check, Duration.ofMillis(10), Duration.ofMillis(100));

  ReadinessHealthProviderTest() {
    completing.countDown();
  }

  private @Nullable List<String> check() {
    runs.incrementAndGet();
    try {
      completing.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return down.get();
  }

  @AfterEach
  void tearDown() {
    provider.stop();
  }

  @Test
  void isHealthyUntilStarted() {
    down.set(List.of("database"));

    assertThat(provider.health()).isEqualTo(Health.HEALTHY);
    assertThat(runs).hasValue(0);
  }

  @Test
  void isUnhealthyWhileAnyCheckIsDown() {
    provider.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() > 0);
    assertThat(provider.health()).isEqualTo(Health.HEALTHY);

    down.set(List.of("database", "cluster"));

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(provider.health())
        .isEqualTo(new Health(false, Map.of("readinessHealthChecksDown", List.of("database", "cluster")))));

    down.set(null);

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(provider.health()).isEqualTo(Health.HEALTHY));
  }

  @Test
  void isUnhealthyWhileTheChecksCannotRun() {
    down.set(List.of());
    provider.start();

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(provider.health())
        .isEqualTo(new Health(false, Map.of("readinessHealthChecks", "could not run"))));
  }

  @Test
  void isUnhealthyWhileTheChecksFail() {
    ReadinessHealthProvider provider = new ReadinessHealthProvider(() -> {
      runs.incrementAndGet();
      throw new IllegalStateException("no checks");
    }, Duration.ofMillis(10), Duration.ofMillis(100));
    provider.start();
    try {
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(provider.health())
          .isEqualTo(new Health(false, Map.of("readinessHealthChecks", "could not run"))));
      // the checks keep running
      int ran = runs.get();
      await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() > ran);
    } finally {
      provider.stop();
    }
  }

  @Test
  void isUnhealthyWhileTheChecksRunForTooLong() {
    // the checks wait for what they check, such as a connection to the database
    CountDownLatch waiting = new CountDownLatch(1);
    ReadinessHealthProvider provider = new ReadinessHealthProvider(() -> {
      try {
        waiting.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }, Duration.ofMillis(10), Duration.ofMillis(500));
    provider.start();
    try {
      // not right away, as the checks may take a while
      assertThat(provider.health()).isEqualTo(Health.HEALTHY);
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        Health health = provider.health();
        assertThat(health.healthy()).isFalse();
        assertThat(health.details()).containsOnlyKeys("readinessHealthChecks");
        assertThat(health.details().get("readinessHealthChecks")).asString().startsWith("running for ");
      });

      waiting.countDown();

      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(provider.health()).isEqualTo(Health.HEALTHY));
    } finally {
      waiting.countDown();
      provider.stop();
    }
  }

  @Test
  void delaysTheUnhealthyState() {
    down.set(List.of("database"));
    provider.setUnhealthyDelay(Duration.ofMinutes(5));
    provider.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() > 1);

    assertThat(provider.health()).isEqualTo(Health.HEALTHY);
  }

  @Test
  void stopsRunningTheChecksOnceStopped() {
    provider.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() > 0);

    provider.stop();

    await().atMost(Duration.ofSeconds(5)).until(() -> {
      int ran = runs.get();
      Thread.sleep(100);
      return runs.get() == ran;
    });
    // and cannot be started again
    int ran = runs.get();
    provider.start();
    await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).until(() -> runs.get() == ran);
  }
}
