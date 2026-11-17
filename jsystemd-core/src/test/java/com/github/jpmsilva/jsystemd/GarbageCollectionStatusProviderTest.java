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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.GarbageCollectorMXBean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

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

  private static GarbageCollectorMXBean collector(String name, long count, long timeMillis) {
    GarbageCollectorMXBean collector = mock(GarbageCollectorMXBean.class);
    when(collector.getName()).thenReturn(name);
    when(collector.getCollectionCount()).thenReturn(count);
    when(collector.getCollectionTime()).thenReturn(timeMillis);
    return collector;
  }
}
