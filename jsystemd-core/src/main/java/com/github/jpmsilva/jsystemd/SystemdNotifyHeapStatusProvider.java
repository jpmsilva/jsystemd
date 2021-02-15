/*
 * Copyright 2018 Joao Silva
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

import com.jakewharton.byteunits.BinaryByteUnit;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation of {@link SystemdNotifyStatusProvider} that provides information regarding the heap zone of the memory.
 *
 * @author Joao Silva
 */
public class SystemdNotifyHeapStatusProvider implements SystemdNotifyStatusProvider {

  @Override
  public @NotNull String status() {
    return Optional.ofNullable(ManagementFactory.getMemoryMXBean())
        .map(MemoryMXBean::getHeapMemoryUsage)
        .map(t -> String.format("Heap: %s/%s", BinaryByteUnit.format(t.getUsed()), BinaryByteUnit.format(t.getCommitted())))
        .orElse("");
  }
}
