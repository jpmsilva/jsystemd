/*
 * Copyright 2018-2026 Joao Silva
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
import java.lang.management.MemoryMXBean;
import java.util.Locale;
import java.util.Optional;

/**
 * Implementation of {@link StatusProvider} that provides the memory used by the heap, out of its committed size, such as
 * {@code Heap: 139.5 MiB/256.0 MiB}.
 *
 * <p>The committed size is the memory the JVM currently holds for the heap, which it grows and shrinks as needed, up to the maximum size of the heap
 * ({@code -Xmx}): it is not a limit, so a heap whose used memory is close to it is not about to run out of memory. The used memory also swings with every
 * allocation and collection; the {@link OldGenerationStatusProvider} tells how close the heap is to its maximum size, from what survives the collections.
 *
 * @author Joao Silva
 */
public class HeapStatusProvider implements StatusProvider {

  /**
   * Create a new HeapStatusProvider.
   */
  public HeapStatusProvider() {
  }

  @Override
  public String status() {
    return Optional.ofNullable(ManagementFactory.getMemoryMXBean())
        .map(MemoryMXBean::getHeapMemoryUsage)
        .map(t -> String.format(Locale.ROOT, "Heap: %s/%s", formatByteCount(t.getUsed()), formatByteCount(t.getCommitted())))
        .orElse("");
  }
}
