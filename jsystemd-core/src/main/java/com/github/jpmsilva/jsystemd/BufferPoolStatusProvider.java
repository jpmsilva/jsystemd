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

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Implementation of {@link StatusProvider} that provides the memory used by the direct and mapped buffers, such as
 * {@code Buffers: 64.0 MiB/1.0 GiB direct (412); 2.0 GiB mapped (3)}.
 *
 * <p>Buffers live outside of the heap, so they are neither part of the heap nor of the non-heap memory reported by the other status providers. Libraries
 * that do their own I/O, such as Netty, allocate direct buffers, and leaking them eventually fails with {@code OutOfMemoryError: Direct buffer memory} when
 * the limit of direct memory is reached.
 *
 * <p>The direct buffers are always reported, with their limit when it is known: the one set with {@code -XX:MaxDirectMemorySize}, or the maximum heap
 * size otherwise, as the JVM does. Mapped buffers are only reported while there are any.
 *
 * @author Joao Silva
 */
public class BufferPoolStatusProvider implements StatusProvider {

  private final List<BufferPoolMXBean> pools;

  private final long directLimit;

  /**
   * Create a new BufferPoolStatusProvider.
   */
  public BufferPoolStatusProvider() {
    this(ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class), directLimit());
  }

  BufferPoolStatusProvider(List<BufferPoolMXBean> pools, long directLimit) {
    this.pools = List.copyOf(pools);
    this.directLimit = directLimit;
  }

  /**
   * Returns the limit of direct memory, or -1 if it cannot be read: the diagnostic bean is specific to HotSpot based JVMs.
   */
  private static long directLimit() {
    try {
      com.sun.management.HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
      long limit = diagnostics == null ? -1 : Long.parseLong(diagnostics.getVMOption("MaxDirectMemorySize").getValue());
      // 0 when not set, in which case the JVM limits direct memory to the maximum heap size
      return limit == 0 ? Runtime.getRuntime().maxMemory() : limit;
    } catch (RuntimeException | LinkageError e) {
      return -1;
    }
  }

  @Override
  public String status() {
    List<String> details = new ArrayList<>();
    // the pools of the JVM are named "direct" and "mapped"; the one for mapped persistent memory, "mapped - 'non-volatile memory'", is left out
    pool("direct").ifPresent(pool -> details.add(directLimit > 0
        ? String.format(Locale.ROOT, "%s/%s direct (%d)", formatByteCount(pool.getMemoryUsed()), formatByteCount(directLimit), pool.getCount())
        : String.format(Locale.ROOT, "%s direct (%d)", formatByteCount(pool.getMemoryUsed()), pool.getCount())));
    pool("mapped").filter(pool -> pool.getCount() > 0).ifPresent(pool ->
        details.add(String.format(Locale.ROOT, "%s mapped (%d)", formatByteCount(pool.getMemoryUsed()), pool.getCount())));
    return details.isEmpty() ? "" : "Buffers: " + String.join("; ", details);
  }

  private Optional<BufferPoolMXBean> pool(String name) {
    return pools.stream().filter(pool -> pool.getName().equals(name)).findFirst();
  }
}
