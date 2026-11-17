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
 * Implementation of {@link StatusProvider} that provides the memory used outside of the heap by the JVM, such as by the metaspace and the code cache, out
 * of its committed size, such as {@code Non-heap: 62.7 MiB/64.1 MiB}.
 *
 * <p>The committed size is the memory the JVM currently holds for it, which it grows as needed: it is not a limit, as the non-heap memory usually has none.
 * The direct and mapped buffers are not part of it, and are reported by the {@link BufferPoolStatusProvider}.
 *
 * @author Joao Silva
 */
public class NonHeapStatusProvider implements StatusProvider {

  /**
   * Create a new NonHeapStatusProvider.
   */
  public NonHeapStatusProvider() {
  }

  @Override
  public String status() {
    return Optional.ofNullable(ManagementFactory.getMemoryMXBean())
        .map(MemoryMXBean::getNonHeapMemoryUsage)
        .map(t -> String.format(Locale.ROOT, "Non-heap: %s/%s", formatByteCount(t.getUsed()), formatByteCount(t.getCommitted())))
        .orElse("");
  }
}
