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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.formatByteCount;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the memory used by the old generation of the heap after the last garbage collection,
 * out of its maximum size (or its committed size, when it has no maximum), such as {@code Old gen after GC: 120.0 MiB/512.0 MiB}.
 *
 * <p>The heap usage reported by the {@link HeapStatusProvider} swings with every allocation and collection, while the old generation after a collection
 * only holds the objects that survived: when it keeps growing, the application is likely leaking memory.
 *
 * <p>The old generation is the heap memory pool named as such by the garbage collector, such as {@code G1 Old Gen} or {@code Tenured Gen}. Collectors
 * without generations, such as non-generational ZGC or Shenandoah, have a single heap memory pool, which is reported instead. Nothing is reported until the
 * pool is first collected.
 *
 * @author Joao Silva
 */
public class OldGenerationStatusProvider implements StatusProvider {

  @Nullable
  private final MemoryPoolMXBean pool;

  /**
   * Create a new OldGenerationStatusProvider.
   */
  public OldGenerationStatusProvider() {
    this(ManagementFactory.getMemoryPoolMXBeans());
  }

  OldGenerationStatusProvider(List<MemoryPoolMXBean> pools) {
    this.pool = oldGeneration(pools);
  }

  // for tests: the name of the memory pool reported, or null if there is none
  @Nullable
  String poolName() {
    return pool == null ? null : pool.getName();
  }

  private static @Nullable MemoryPoolMXBean oldGeneration(List<MemoryPoolMXBean> pools) {
    // only the pools that are garbage collected report their usage after a collection
    List<MemoryPoolMXBean> heap = pools.stream().filter(pool -> pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()).toList();
    for (MemoryPoolMXBean pool : heap) {
      // "G1 Old Gen", "PS Old Gen", "Tenured Gen", or "ZGC Old Generation" under generational ZGC
      String name = pool.getName();
      if (name.contains("Old") || name.contains("Tenured")) {
        return pool;
      }
    }
    // collectors without generations have a single heap pool, such as "ZHeap" or "Shenandoah"
    return heap.size() == 1 ? heap.get(0) : null;
  }

  @Override
  public String status() {
    MemoryPoolMXBean pool = this.pool;
    if (pool == null) {
      return "";
    }
    MemoryUsage usage = pool.getCollectionUsage();
    // all zeros until the pool is first collected
    if (usage == null || usage.getCommitted() == 0) {
      return "";
    }
    // -1 when the pool has no maximum size
    long size = usage.getMax() >= 0 ? usage.getMax() : usage.getCommitted();
    return String.format(Locale.ROOT, "Old gen after GC: %s/%s", formatByteCount(usage.getUsed()), formatByteCount(size));
  }
}
