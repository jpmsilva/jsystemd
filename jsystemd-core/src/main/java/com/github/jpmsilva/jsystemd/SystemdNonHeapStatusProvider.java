/*
 * Copyright 2018-2023 Joao Silva
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
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdStatusProvider} that provides information regarding the non-heap zone of the memory.
 *
 * @author Joao Silva
 */
@Order(-2000)
public class SystemdNonHeapStatusProvider implements SystemdStatusProvider {

  /**
   * Create a new SystemdNonHeapStatusProvider.
   */
  public SystemdNonHeapStatusProvider() {
  }

  @Override
  public @NotNull String status() {
    return Optional.ofNullable(ManagementFactory.getMemoryMXBean())
        .map(MemoryMXBean::getNonHeapMemoryUsage)
        .map(t -> String.format("Non-heap: %s/%s", formatByteCount(t.getUsed()), formatByteCount(t.getCommitted())))
        .orElse("");
  }
}
