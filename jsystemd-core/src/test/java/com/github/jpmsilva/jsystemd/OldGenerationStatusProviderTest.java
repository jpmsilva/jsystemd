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

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OldGenerationStatusProvider}.
 *
 * @author Joao Silva
 */
class OldGenerationStatusProviderTest {

  private static final long MIB = 1024 * 1024;

  @Test
  void reportsTheOldGenerationOfThisJvmOnceCollected() {
    OldGenerationStatusProvider provider = new OldGenerationStatusProvider();
    // the JVM picks the collector from the machine it runs on, such as G1 ("G1 Old Gen"), or Serial ("Tenured Gen") on small machines
    assertThat(provider.poolName()).isNotNull().containsAnyOf("Old", "Tenured");

    // collects the old generation, as the tests do not disable explicit garbage collection
    System.gc();

    assertThat(provider.status()).matches("Old gen after GC: [0-9.,]+ [KMG]?i?B/[0-9.,]+ [KMG]?i?B");
  }

  @Test
  void reportsTheOldGenerationAfterTheLastCollection() {
    MemoryPoolMXBean old = pool("G1 Old Gen", MemoryType.HEAP, new MemoryUsage(0, 120 * MIB, 256 * MIB, 512 * MIB));
    List<MemoryPoolMXBean> pools = List.of(pool("G1 Eden Space", MemoryType.HEAP, null), old, pool("Metaspace", MemoryType.NON_HEAP, null));

    assertThat(new OldGenerationStatusProvider(pools).status()).isEqualTo("Old gen after GC: 120.0 MiB/512.0 MiB");
  }

  @Test
  void reportsTheCommittedSizeWhenThereIsNoMaximum() {
    MemoryPoolMXBean old = pool("Tenured Gen", MemoryType.HEAP, new MemoryUsage(0, 120 * MIB, 256 * MIB, -1));

    assertThat(new OldGenerationStatusProvider(List.of(old)).status()).isEqualTo("Old gen after GC: 120.0 MiB/256.0 MiB");
  }

  @Test
  void reportsTheSingleHeapPoolOfCollectorsWithoutGenerations() {
    MemoryPoolMXBean heap = pool("ZHeap", MemoryType.HEAP, new MemoryUsage(0, 120 * MIB, 256 * MIB, 512 * MIB));

    OldGenerationStatusProvider provider = new OldGenerationStatusProvider(List.of(heap, pool("CodeHeap 'non-nmethods'", MemoryType.NON_HEAP, null)));

    assertThat(provider.poolName()).isEqualTo("ZHeap");
  }

  @Test
  void reportsNothingWhenTheOldGenerationCannotBeTold() {
    List<MemoryPoolMXBean> pools = List.of(pool("Young", MemoryType.HEAP, null), pool("Other", MemoryType.HEAP, null));

    OldGenerationStatusProvider provider = new OldGenerationStatusProvider(pools);

    assertThat(provider.poolName()).isNull();
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsNothingUntilCollected() {
    MemoryPoolMXBean old = pool("G1 Old Gen", MemoryType.HEAP, new MemoryUsage(0, 0, 0, 512 * MIB));

    assertThat(new OldGenerationStatusProvider(List.of(old)).status()).isEmpty();
  }

  private static MemoryPoolMXBean pool(String name, MemoryType type, MemoryUsage collectionUsage) {
    MemoryPoolMXBean pool = mock(MemoryPoolMXBean.class);
    when(pool.getName()).thenReturn(name);
    when(pool.getType()).thenReturn(type);
    when(pool.isCollectionUsageThresholdSupported()).thenReturn(type == MemoryType.HEAP);
    when(pool.getCollectionUsage()).thenReturn(collectionUsage);
    return pool;
  }
}
