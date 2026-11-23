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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayableHealthProvider}.
 *
 * @author Joao Silva
 */
class DelayableHealthProviderTest {

  private static final HealthProvider.Health UNHEALTHY = new HealthProvider.Health(false, Map.of("db", "down"));

  // the value of nanoTime, which can be anything, even negative
  private final AtomicLong nanoTime = new AtomicLong(-1_000_000_000_000L);

  private final TestHealthProvider provider = new TestHealthProvider(nanoTime);

  private void advance(Duration duration) {
    nanoTime.addAndGet(duration.toNanos());
  }

  @Test
  void reportsTheUnhealthyStateRightAwayWithoutDelay() {
    provider.health = UNHEALTHY;

    assertThat(provider.getUnhealthyDelay()).isNull();
    assertThat(provider.health()).isEqualTo(UNHEALTHY);
  }

  @Test
  void reportsTheUnhealthyStateOnceTheDelayElapses() {
    provider.setUnhealthyDelay(Duration.ofSeconds(60));
    provider.health = UNHEALTHY;

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    advance(Duration.ofSeconds(59));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    advance(Duration.ofSeconds(1));
    assertThat(provider.health()).isEqualTo(UNHEALTHY);
  }

  @Test
  void keepsAnUnrecoverableStateUnrecoverableOnceTheDelayElapses() {
    HealthProvider.Health unrecoverable = HealthProvider.Health.unrecoverable(Map.of("livenessState", "BROKEN"));
    provider.setUnhealthyDelay(Duration.ofSeconds(60));
    provider.health = unrecoverable;

    // the watchdog is not triggered while the unhealthy state is delayed
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    advance(Duration.ofSeconds(60));
    assertThat(provider.health()).isEqualTo(unrecoverable);
  }

  @Test
  void reportsTheUnhealthyStateRightAwayWithAZeroDelay() {
    provider.setUnhealthyDelay(Duration.ZERO);
    provider.health = UNHEALTHY;

    assertThat(provider.health()).isEqualTo(UNHEALTHY);
  }

  @Test
  void restartsTheDelayOnceHealthyAgain() {
    provider.setUnhealthyDelay(Duration.ofSeconds(60));
    provider.health = UNHEALTHY;
    provider.health();
    advance(Duration.ofSeconds(50));

    provider.health = HealthProvider.Health.HEALTHY;
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    provider.health = UNHEALTHY;
    advance(Duration.ofSeconds(50));

    // unhealthy for 100 seconds in total, but only for the last 50 without interruption
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysFromWhenTheUnhealthyStateWasFirstSeenEvenBeforeTheDelayWasSet() {
    provider.health = UNHEALTHY;
    provider.health();
    advance(Duration.ofSeconds(60));

    provider.setUnhealthyDelay(Duration.ofSeconds(60));

    assertThat(provider.health()).isEqualTo(UNHEALTHY);
  }

  @Test
  void measuresTheDelayAcrossTheOverflowOfTheNanoTime() {
    nanoTime.set(Long.MAX_VALUE - Duration.ofSeconds(30).toNanos());
    provider.setUnhealthyDelay(Duration.ofSeconds(60));
    provider.health = UNHEALTHY;

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    advance(Duration.ofSeconds(59));
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    advance(Duration.ofSeconds(1));
    assertThat(provider.health()).isEqualTo(UNHEALTHY);
  }

  @Test
  void rejectsNegativeDelays() {
    assertThatThrownBy(() -> provider.setUnhealthyDelay(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    assertThat(provider.getUnhealthyDelay()).isNull();
  }

  private static class TestHealthProvider extends DelayableHealthProvider {

    private HealthProvider.Health health = HealthProvider.Health.HEALTHY;

    TestHealthProvider(AtomicLong nanoTime) {
      super(nanoTime::get);
    }

    @Override
    protected HealthProvider.Health evaluateHealth() {
      return health;
    }
  }
}
