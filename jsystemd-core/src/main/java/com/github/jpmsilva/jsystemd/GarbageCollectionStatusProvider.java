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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Provides information regarding the garbage collection pauses: as a {@link StatusProvider}, the pauses since the status was last read, or since this
 * provider was created, how many there were, and the share of the time they took, such as {@code GC: 12 pauses, 0.4% of time}, and as a
 * {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while it spends most of its time collecting garbage, if
 * enabled.
 *
 * <p>A share that keeps growing means the application spends more and more of its time collecting garbage, usually as it runs out of heap. An
 * application that is about to run out of heap can spend minutes, or hours, collecting garbage back to back, each collection freeing just enough for the
 * next few allocations, before it fails with an {@link OutOfMemoryError}, if it ever does: it is up, but hardly does any work.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while the share of the time taken by the pauses, since the health was last evaluated, is at or above the given threshold, reporting the pauses
 * and their share in the health details, under {@code gcPauses} and {@code gcPausedPercent}. As a burst of allocations can also keep the collector busy
 * for a while, the unhealthy state should be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}: the share must then stay at or above the
 * threshold, every time the health is evaluated, for that long.
 *
 * <p>Only pauses are counted. The concurrent collectors, such as ZGC and Shenandoah, also report the time of their concurrent cycles, which run alongside
 * the application rather than pausing it, and are left out. An application that runs out of heap under such a collector stalls on its allocations rather
 * than pausing, which the health does not detect.
 *
 * @author Joao Silva
 */
public class GarbageCollectionStatusProvider extends DelayableHealthProvider implements StatusProvider {

  private final List<GarbageCollectorMXBean> collectors;

  private final boolean health;

  private final double threshold;

  // the pauses are measured since the status was last read, and since the health was last evaluated, each at its own period; guarded by this
  private final Measure statusMeasure;
  private final Measure healthMeasure;

  /**
   * Create a new GarbageCollectionStatusProvider, which reports the pauses since it was created on the first read, and is always healthy.
   */
  public GarbageCollectionStatusProvider() {
    this(false, 1);
  }

  /**
   * Create a new GarbageCollectionStatusProvider, which reports the pauses since it was created on the first read.
   *
   * @param health <code>true</code> to report the application as unhealthy while it spends most of its time paused collecting garbage
   * @param threshold the share of the time taken by the pauses from which the application is unhealthy, such as <code>0.5</code> for 50%
   * @throws IllegalArgumentException if the threshold is not greater than 0 and at most 1
   */
  public GarbageCollectionStatusProvider(boolean health, double threshold) {
    this(ManagementFactory.getGarbageCollectorMXBeans(), System::nanoTime, health, threshold);
  }

  GarbageCollectionStatusProvider(List<GarbageCollectorMXBean> collectors, LongSupplier nanoTime) {
    this(collectors, nanoTime, false, 1);
  }

  GarbageCollectionStatusProvider(List<GarbageCollectorMXBean> collectors, LongSupplier nanoTime, boolean health, double threshold) {
    super(nanoTime);
    if (!(threshold > 0 && threshold <= 1)) {
      throw new IllegalArgumentException("Threshold must be greater than 0 and at most 1");
    }
    // the concurrent collectors have a bean for their pauses and another for their cycles, such as "ZGC Pauses" and "ZGC Cycles", or "ZGC Minor Pauses"
    // and "ZGC Minor Cycles" under generational ZGC
    this.collectors = collectors.stream().filter(collector -> !collector.getName().contains("Cycles")).toList();
    this.health = health;
    this.threshold = threshold;
    this.statusMeasure = new Measure(nanoTime);
    this.healthMeasure = new Measure(nanoTime);
  }

  // for tests: whether the time taken by the pauses reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  // for tests: the share of the time taken by the pauses from which the application is unhealthy
  double threshold() {
    return threshold;
  }

  private long count() {
    // -1 when a collector does not report it
    return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(count -> count > 0).sum();
  }

  private long timeMillis() {
    return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(time -> time > 0).sum();
  }

  @Override
  public synchronized String status() {
    if (collectors.isEmpty()) {
      return "";
    }
    Pauses pauses = statusMeasure.pauses();
    return String.format(Locale.ROOT, "GC: %d %s, %.1f%% of time", pauses.count(), pauses.count() == 1 ? "pause" : "pauses", pauses.percent());
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while the share of the time taken by the pauses, since the health was last
   * evaluated, is at or above the threshold, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected synchronized Health evaluateHealth() {
    if (!health || collectors.isEmpty()) {
      return Health.HEALTHY;
    }
    Pauses pauses = healthMeasure.pauses();
    if (pauses.percent() < 100.0 * threshold) {
      return Health.HEALTHY;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("gcPauses", pauses.count());
    details.put("gcPausedPercent", String.format(Locale.ROOT, "%.1f", pauses.percent()));
    return new Health(false, details);
  }

  // the pauses over a period: how many there were, and the share of the time they took, as a percentage
  private record Pauses(long count, double percent) {

  }

  /**
   * Measures the pauses since it was last asked for them, or since it was created.
   */
  private final class Measure {

    private final LongSupplier nanoTime;

    private long lastCount;
    private long lastTimeMillis;
    private long lastNanos;

    private Measure(LongSupplier nanoTime) {
      this.nanoTime = nanoTime;
      this.lastCount = count();
      this.lastTimeMillis = timeMillis();
      this.lastNanos = nanoTime.getAsLong();
    }

    private Pauses pauses() {
      long count = count();
      long timeMillis = timeMillis();
      long nanos = nanoTime.getAsLong();
      final long elapsedNanos = nanos - lastNanos;
      // the counters only grow, but a collector may stop reporting them, so the differences are kept positive
      final long pauses = Math.max(0, count - lastCount);
      final long pausedNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeMillis - lastTimeMillis));
      lastCount = count;
      lastTimeMillis = timeMillis;
      lastNanos = nanos;
      // the pause times are only measured in milliseconds, so the share is capped at the time that passed
      return new Pauses(pauses, elapsedNanos > 0 ? Math.min(100.0, 100.0 * pausedNanos / elapsedNanos) : 0.0);
    }
  }
}
