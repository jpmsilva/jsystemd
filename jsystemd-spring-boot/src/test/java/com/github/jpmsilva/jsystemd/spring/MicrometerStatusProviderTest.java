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

import com.github.jpmsilva.jsystemd.spring.MicrometerStatusProvider.ShownMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link MicrometerStatusProvider}, with meters of a registry that really measures.
 *
 * @author Joao Silva
 */
class MicrometerStatusProviderTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();

  private MicrometerStatusProvider provider(ShownMeter... meters) {
    return new MicrometerStatusProvider(registry, List.of(meters));
  }

  private String status(String name) {
    return provider(new ShownMeter(name)).status();
  }

  @Test
  void showsTheValueOfAGauge() {
    AtomicInteger active = new AtomicInteger(7);
    Gauge.builder("hikaricp.connections.active", active, AtomicInteger::get).register(registry);

    assertThat(status("hikaricp.connections.active")).isEqualTo("hikaricp.connections.active: 7");

    active.set(8);

    assertThat(status("hikaricp.connections.active")).isEqualTo("hikaricp.connections.active: 8");
  }

  @Test
  void showsTheCountOfACounter() {
    Counter counter = Counter.builder("orders.placed").register(registry);
    counter.increment();
    counter.increment(2);
    FunctionCounter.builder("cache.gets", new AtomicInteger(40), AtomicInteger::get).register(registry);

    assertThat(provider(new ShownMeter("orders.placed"), new ShownMeter("cache.gets")).status()).isEqualTo("orders.placed: 3, cache.gets: 40");
  }

  @Test
  void showsHowManyTimesATimerOrADistributionSummaryRecorded() {
    Timer timer = Timer.builder("http.server.requests").register(registry);
    timer.record(Duration.ofSeconds(3));
    timer.record(Duration.ofSeconds(5));
    DistributionSummary summary = DistributionSummary.builder("response.size").baseUnit("bytes").register(registry);
    summary.record(4096);

    // neither the times nor the amounts recorded, and the count of a summary of bytes is not in bytes
    assertThat(provider(new ShownMeter("http.server.requests"), new ShownMeter("response.size")).status())
        .isEqualTo("http.server.requests: 2, response.size: 1");
  }

  @Test
  void showsTheRunningTasksOfALongTaskTimer() {
    LongTaskTimer timer = LongTaskTimer.builder("batch.jobs").register(registry);
    timer.start();
    LongTaskTimer.Sample sample = timer.start();

    assertThat(status("batch.jobs")).isEqualTo("batch.jobs: 2");

    sample.stop();

    assertThat(status("batch.jobs")).isEqualTo("batch.jobs: 1");
  }

  @Test
  void showsTheValueOfATimeGaugeInItsUnit() {
    TimeGauge.builder("process.uptime", () -> 90_500, TimeUnit.MILLISECONDS).register(registry);

    // the simple registry measures time in seconds
    assertThat(status("process.uptime")).isEqualTo("process.uptime: 90.5");
  }

  @Test
  void showsTheFirstShownStatisticOfACustomMeterAndLeavesOutThoseWithoutAny() {
    Meter.builder("custom.total", Meter.Type.OTHER, List.of(new Measurement(() -> 12.0, Statistic.TOTAL), new Measurement(() -> 3.0, Statistic.COUNT)))
        .register(registry);
    Meter.builder("custom.unknown", Meter.Type.OTHER, List.of(new Measurement(() -> 12.0, Statistic.UNKNOWN))).register(registry);

    assertThat(provider(new ShownMeter("custom.total"), new ShownMeter("custom.unknown")).status()).isEqualTo("custom.total: 3");
  }

  @Test
  void addsTogetherTheMetersWithTheSameNameAsTheMetricsEndpointDoes() {
    Counter.builder("http.client.requests").tag("status", "200").register(registry).increment(5);
    Counter.builder("http.client.requests").tag("status", "500").register(registry).increment(2);

    assertThat(status("http.client.requests")).isEqualTo("http.client.requests: 7");
  }

  @Test
  void narrowsTheMetersWithTags() {
    Gauge.builder("jvm.memory.used", () -> 1024).tag("area", "heap").tag("id", "G1 Eden Space").register(registry);
    Gauge.builder("jvm.memory.used", () -> 2048).tag("area", "heap").tag("id", "G1 Old Gen").register(registry);
    Gauge.builder("jvm.memory.used", () -> 4096).tag("area", "nonheap").tag("id", "Metaspace").register(registry);
    Map<String, String> oldGeneration = new LinkedHashMap<>();
    oldGeneration.put("area", "heap");
    oldGeneration.put("id", "G1 Old Gen");

    assertThat(provider(new ShownMeter("jvm.memory.used", Map.of("area", "heap"), null), new ShownMeter("jvm.memory.used", oldGeneration, null)).status())
        .isEqualTo("jvm.memory.used: 3072, jvm.memory.used: 2048");
  }

  @Test
  void showsTheMeterUnderItsLabel() {
    Gauge.builder("hikaricp.connections.active", () -> 7).tag("pool", "HikariPool-1").register(registry);

    assertThat(provider(new ShownMeter("hikaricp.connections.active", Map.of("pool", "HikariPool-1"), "Database connections")).status())
        .isEqualTo("Database connections: 7");
    // a blank label is the same as none
    assertThat(provider(new ShownMeter("hikaricp.connections.active", Map.of(), " ")).status()).isEqualTo("hikaricp.connections.active: 7");
  }

  @Test
  void showsTheMetersInTheirOrder() {
    Gauge.builder("b", () -> 1).register(registry);
    Gauge.builder("a", () -> 2).register(registry);

    assertThat(provider(new ShownMeter("b"), new ShownMeter("a")).status()).isEqualTo("b: 1, a: 2");
  }

  @Test
  void leavesOutMetersThatDoNotExistUntilTheyDo() {
    MicrometerStatusProvider provider = provider(new ShownMeter("orders.placed"), new ShownMeter("orders.failed", Map.of("reason", "payment"), null));

    assertThat(provider.status()).isEmpty();

    Counter.builder("orders.placed").register(registry).increment();
    // the name exists, but not with the tag
    Counter.builder("orders.failed").tag("reason", "stock").register(registry).increment();

    assertThat(provider.status()).isEqualTo("orders.placed: 1");
  }

  @Test
  void leavesOutGaugesWithoutAValue() {
    Gauge.builder("queue.size", () -> Double.NaN).register(registry);

    assertThat(status("queue.size")).isEmpty();
  }

  @Test
  void showsNothingWithoutMeters() {
    assertThat(provider().status()).isEmpty();
  }

  @Test
  void showsTheMetersOfACompositeRegistry() {
    // as the registry Spring Boot sets up, which measures with the registries it is made of
    CompositeMeterRegistry composite = new CompositeMeterRegistry();
    composite.add(registry);
    Counter.builder("orders.placed").register(composite).increment(3);

    assertThat(new MicrometerStatusProvider(composite, List.of(new ShownMeter("orders.placed"))).status()).isEqualTo("orders.placed: 3");
  }

  @Test
  void showsValuesInBytesAsTheMemoryStatusProviders() {
    Gauge.builder("jvm.memory.used", () -> 139_670_323).baseUnit("bytes").register(registry);
    Gauge.builder("disk.free", () -> 512).baseUnit("bytes").register(registry);

    assertThat(provider(new ShownMeter("jvm.memory.used"), new ShownMeter("disk.free")).status()).isEqualTo("jvm.memory.used: 133.2 MiB, disk.free: 512.0 B");
  }

  @ParameterizedTest
  @CsvSource({
      "0, 0",
      "7, 7",
      "-7, -7",
      "15234, 15234",
      "999999, 999999",
      "0.5, 0.5",
      "0.125, 0.13",
      "12.3456, 12.35",
      "0.001, 0",
      "-0.001, 0",
      "1000000, 1M",
      "1500000, 1.5M",
      "-1500000, -1.5M",
      "2340000000, 2.3G",
      "4000000000000, 4T",
      "5000000000000000, 5000T"
  })
  void formatsNumbersCompactly(double value, String formatted) {
    assertThat(MicrometerStatusProvider.format(value)).isEqualTo(formatted);
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "0; 0.0 B",
      "1023; 1,023.0 B",
      "1024; 1.0 KiB",
      "1610612736; 1.5 GiB",
      "-2048; -2.0 KiB"
  })
  void formatsBytes(double value, String formatted) {
    assertThat(MicrometerStatusProvider.formatBytes(value)).isEqualTo(formatted);
  }

  @Test
  void showsRegardlessOfDefaultLocale() {
    Gauge.builder("ratio", () -> 0.75).register(registry);
    Gauge.builder("memory", () -> 1536).baseUnit("bytes").register(registry);
    Gauge.builder("count", () -> 1_500_000).register(registry);
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٠" for 10, and another decimal separator
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(provider(new ShownMeter("ratio"), new ShownMeter("memory"), new ShownMeter("count")).status())
          .isEqualTo("ratio: 0.75, memory: 1.5 KiB, count: 1.5M");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void rejectsAMeterWithoutAName() {
    assertThatThrownBy(() -> new ShownMeter(" ")).isInstanceOf(IllegalArgumentException.class).hasMessage("Name must not be blank");
  }

  @Test
  void keepsItsOwnCopyOfTheMetersAndOfTheirTags() {
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put("area", "heap");
    ShownMeter meter = new ShownMeter("jvm.memory.used", tags, null);
    tags.put("id", "G1 Old Gen");

    assertThat(meter.tags()).containsOnlyKeys("area");
    assertThatThrownBy(() -> meter.tags().put("id", "G1 Old Gen")).isInstanceOf(UnsupportedOperationException.class);
    assertThat(provider(meter).meters()).containsExactly(meter);
  }
}
