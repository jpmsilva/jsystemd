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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Implementation of {@link StatusProvider} that provides the garbage collection pauses since the status was last read, or since this provider was
 * created: how many there were, and the share of the time they took, such as {@code GC: 12 pauses, 0.4% of time}. A share that keeps growing means the
 * application spends more and more of its time collecting garbage, usually as it runs out of heap.
 *
 * <p>Only pauses are counted. The concurrent collectors, such as ZGC and Shenandoah, also report the time of their concurrent cycles, which run alongside
 * the application rather than pausing it, and are left out.
 *
 * @author Joao Silva
 */
public class GarbageCollectionStatusProvider implements StatusProvider {

  private final List<GarbageCollectorMXBean> collectors;

  private final LongSupplier nanoTime;

  // guarded by this
  private long lastCount;

  // guarded by this
  private long lastTimeMillis;

  // guarded by this
  private long lastNanos;

  /**
   * Create a new GarbageCollectionStatusProvider, which reports the pauses since it was created on the first read.
   */
  public GarbageCollectionStatusProvider() {
    this(ManagementFactory.getGarbageCollectorMXBeans(), System::nanoTime);
  }

  GarbageCollectionStatusProvider(List<GarbageCollectorMXBean> collectors, LongSupplier nanoTime) {
    // the concurrent collectors have a bean for their pauses and another for their cycles, such as "ZGC Pauses" and "ZGC Cycles", or "ZGC Minor Pauses"
    // and "ZGC Minor Cycles" under generational ZGC
    this.collectors = collectors.stream().filter(collector -> !collector.getName().contains("Cycles")).toList();
    this.nanoTime = nanoTime;
    this.lastCount = count();
    this.lastTimeMillis = timeMillis();
    this.lastNanos = nanoTime.getAsLong();
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
    double share = elapsedNanos > 0 ? Math.min(100.0, 100.0 * pausedNanos / elapsedNanos) : 0.0;
    return String.format(Locale.ROOT, "GC: %d %s, %.1f%% of time", pauses, pauses == 1 ? "pause" : "pauses", share);
  }
}
