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
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.GarbageCollectorMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link GarbageCollectionStatusProvider}.
 *
 * @author Joao Silva
 */
class GarbageCollectionStatusProviderTest {

  private final AtomicLong nanoTime = new AtomicLong();

  @Test
  void reportsTheGarbageCollectionsOfThisJvmRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(new GarbageCollectionStatusProvider().status()).matches("GC: [0-9]+ pauses?, [0-9]+\\.[0-9]% of time");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void reportsThePausesSinceCreatedOnTheFirstRead() {
    GarbageCollectorMXBean young = collector("G1 Young Generation", 10, 100);
    GarbageCollectorMXBean old = collector("G1 Old Generation", 1, 50);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(young, old), nanoTime::get);

    // 4 more pauses, for 20 milliseconds, over 2 seconds
    when(young.getCollectionCount()).thenReturn(13L);
    when(young.getCollectionTime()).thenReturn(110L);
    when(old.getCollectionCount()).thenReturn(2L);
    when(old.getCollectionTime()).thenReturn(60L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(2));

    assertThat(provider.status()).isEqualTo("GC: 4 pauses, 1.0% of time");
  }

  @Test
  void reportsThePausesSinceTheLastRead() {
    GarbageCollectorMXBean young = collector("G1 Young Generation", 10, 100);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(young), nanoTime::get);
    when(young.getCollectionCount()).thenReturn(20L);
    when(young.getCollectionTime()).thenReturn(200L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));
    provider.status();

    when(young.getCollectionCount()).thenReturn(21L);
    when(young.getCollectionTime()).thenReturn(250L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEqualTo("GC: 1 pause, 5.0% of time");
  }

  @Test
  void leavesOutTheConcurrentCyclesOfTheConcurrentCollectors() {
    GarbageCollectorMXBean pauses = collector("ZGC Major Pauses", 0, 0);
    GarbageCollectorMXBean cycles = collector("ZGC Major Cycles", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(pauses, cycles), nanoTime::get);

    when(pauses.getCollectionCount()).thenReturn(3L);
    when(pauses.getCollectionTime()).thenReturn(1L);
    when(cycles.getCollectionCount()).thenReturn(1L);
    when(cycles.getCollectionTime()).thenReturn(900L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEqualTo("GC: 3 pauses, 0.1% of time");
  }

  @Test
  void ignoresCountersNotReportedYet() {
    // -1 until the collector reports its counters, which must not count as a pause, nor as time, once it does
    GarbageCollectorMXBean collector = collector("Copy", -1, -1);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(collector), nanoTime::get);

    when(collector.getCollectionCount()).thenReturn(5L);
    when(collector.getCollectionTime()).thenReturn(50L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEqualTo("GC: 5 pauses, 5.0% of time");
  }

  @Test
  void reportsNoPausesWhenACollectorStopsReportingItsCounters() {
    GarbageCollectorMXBean collector = collector("Copy", 10, 100);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(collector), nanoTime::get);

    when(collector.getCollectionCount()).thenReturn(-1L);
    when(collector.getCollectionTime()).thenReturn(-1L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEqualTo("GC: 0 pauses, 0.0% of time");
  }

  @Test
  void capsTheShareAtTheTimeThatPassed() {
    GarbageCollectorMXBean collector = collector("Copy", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(collector), nanoTime::get);

    // the pause times are rounded to milliseconds, so they can add up to more than the time that passed
    when(collector.getCollectionCount()).thenReturn(2L);
    when(collector.getCollectionTime()).thenReturn(2L);
    nanoTime.addAndGet(TimeUnit.MICROSECONDS.toNanos(1500));

    assertThat(provider.status()).isEqualTo("GC: 2 pauses, 100.0% of time");
  }

  @Test
  void reportsNothingWithoutCollectors() {
    assertThat(new GarbageCollectionStatusProvider(List.of(), nanoTime::get).status()).isEmpty();
  }

  @Test
  void isUnhealthyFromTheThreshold() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 1, 50);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get, true, 0.5);

    // 14 more pauses, for 5 seconds, over 10 seconds
    when(old.getCollectionCount()).thenReturn(15L);
    when(old.getCollectionTime()).thenReturn(5050L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));

    HealthProvider.Health health = provider.health();

    assertThat(health.healthy()).isFalse();
    // the load may drop, so the application may recover
    assertThat(health.unrecoverable()).isFalse();
    assertThat(health.details()).containsExactly(entry("gcPauses", 14L), entry("gcPausedPercent", "50.0"));
  }

  @Test
  void isHealthyBelowTheThreshold() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 1, 50);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get, true, 0.5);

    when(old.getCollectionCount()).thenReturn(15L);
    when(old.getCollectionTime()).thenReturn(5049L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void evaluatesTheHealthOverThePausesSinceItWasLastEvaluated() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get, true, 0.5);
    when(old.getCollectionCount()).thenReturn(10L);
    when(old.getCollectionTime()).thenReturn(9000L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));
    assertThat(provider.health().healthy()).isFalse();

    // no more pauses since
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void measuresTheStatusAndTheHealthApart() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get, true, 0.5);
    when(old.getCollectionCount()).thenReturn(10L);
    when(old.getCollectionTime()).thenReturn(9000L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));

    // reading the status, which is read at a period of its own, does not start the measure of the health over
    assertThat(provider.status()).isEqualTo("GC: 10 pauses, 90.0% of time");
    assertThat(provider.health().healthy()).isFalse();
    assertThat(provider.status()).isEqualTo("GC: 0 pauses, 0.0% of time");
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get);
    when(old.getCollectionCount()).thenReturn(10L);
    when(old.getCollectionTime()).thenReturn(10000L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));

    assertThat(provider.healthEnabled()).isFalse();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    GarbageCollectorMXBean old = collector("G1 Old Generation", 0, 0);
    GarbageCollectionStatusProvider provider = new GarbageCollectionStatusProvider(List.of(old), nanoTime::get, true, 0.5);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    // paused all the time, for a minute
    for (int i = 1; i <= 5; i++) {
      when(old.getCollectionCount()).thenReturn(10L * i);
      when(old.getCollectionTime()).thenReturn(10000L * i);
      nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(10));
      assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    }
    when(old.getCollectionCount()).thenReturn(70L);
    when(old.getCollectionTime()).thenReturn(70000L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(20));

    assertThat(provider.health().healthy()).isFalse();
  }

  @Test
  void isHealthyWithoutCollectors() {
    assertThat(new GarbageCollectionStatusProvider(List.of(), nanoTime::get, true, 0.5).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -0.5, 1.1, Double.NaN})
  void rejectsThresholdsOutOfRange(double threshold) {
    assertThatThrownBy(() -> new GarbageCollectionStatusProvider(false, threshold)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Threshold");
  }

  private static GarbageCollectorMXBean collector(String name, long count, long timeMillis) {
    GarbageCollectorMXBean collector = mock(GarbageCollectorMXBean.class);
    when(collector.getName()).thenReturn(name);
    when(collector.getCollectionCount()).thenReturn(count);
    when(collector.getCollectionTime()).thenReturn(timeMillis);
    return collector;
  }
}
