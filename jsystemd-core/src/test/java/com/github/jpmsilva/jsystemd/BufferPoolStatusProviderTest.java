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

import java.lang.management.BufferPoolMXBean;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BufferPoolStatusProvider}.
 *
 * @author Joao Silva
 */
class BufferPoolStatusProviderTest {

  private static final long MIB = 1024 * 1024;

  @Test
  void reportsTheDirectBuffersOfThisJvmWithTheirLimit() {
    // at least one direct buffer is allocated
    ByteBuffer buffer = ByteBuffer.allocateDirect(1024);

    assertThat(new BufferPoolStatusProvider().status()).matches("Buffers: [0-9.,]+ [KMG]?i?B/[0-9.,]+ [KMG]?i?B direct \\([1-9][0-9]*\\)");
    assertThat(buffer.capacity()).isEqualTo(1024);
  }

  @Test
  void reportsTheDirectAndMappedBuffers() {
    List<BufferPoolMXBean> pools = List.of(pool("direct", 412, 64 * MIB), pool("mapped", 3, 2048 * MIB),
        pool("mapped - 'non-volatile memory'", 1, MIB));

    assertThat(new BufferPoolStatusProvider(pools, 1024 * MIB).status()).isEqualTo("Buffers: 64.0 MiB/1.0 GiB direct (412); 2.0 GiB mapped (3)");
  }

  @Test
  void leavesOutTheMappedBuffersWhileThereAreNone() {
    List<BufferPoolMXBean> pools = List.of(pool("direct", 0, 0), pool("mapped", 0, 0));

    assertThat(new BufferPoolStatusProvider(pools, 1024 * MIB).status()).isEqualTo("Buffers: 0.0 B/1.0 GiB direct (0)");
  }

  @Test
  void leavesOutTheLimitWhenUnknown() {
    assertThat(new BufferPoolStatusProvider(List.of(pool("direct", 2, MIB)), -1).status()).isEqualTo("Buffers: 1.0 MiB direct (2)");
  }

  @Test
  void reportsNothingWithoutBufferPools() {
    assertThat(new BufferPoolStatusProvider(List.of(), -1).status()).isEmpty();
  }

  private static BufferPoolMXBean pool(String name, long count, long memoryUsed) {
    BufferPoolMXBean pool = mock(BufferPoolMXBean.class);
    when(pool.getName()).thenReturn(name);
    when(pool.getCount()).thenReturn(count);
    when(pool.getMemoryUsed()).thenReturn(memoryUsed);
    return pool;
  }
}
