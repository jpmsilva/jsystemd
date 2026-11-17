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

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the CPU usage, such as {@code CPU: 12.5% process; 40.1% system; load 1.20}: the share of
 * the available processors the process used since the status was last read, or since this provider was created, the recent CPU usage of the whole system,
 * and the system load average over the last minute.
 *
 * <p>Unlike the CPU time shown by {@code systemctl status}, which adds up all the time since the service started, this shows how busy the process is now.
 * The available processors are those the JVM can use, which take into account the CPU quota of the service unit, set by systemd with {@code CPUQuota}.
 *
 * <p>What the operating system does not provide is left out, such as the load average, which is not available on Windows.
 *
 * @author Joao Silva
 */
public class CpuStatusProvider implements StatusProvider {

  @Nullable
  private final OperatingSystemMXBean system;

  private final LongSupplier nanoTime;

  // guarded by this
  private long lastCpuNanos;

  // guarded by this
  private long lastNanos;

  /**
   * Create a new CpuStatusProvider, which reports the CPU usage of the process since it was created on the first read.
   */
  public CpuStatusProvider() {
    this(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean system ? system : null, System::nanoTime);
  }

  CpuStatusProvider(@Nullable OperatingSystemMXBean system, LongSupplier nanoTime) {
    this.system = system;
    this.nanoTime = nanoTime;
    this.lastCpuNanos = system == null ? 0 : system.getProcessCpuTime();
    this.lastNanos = nanoTime.getAsLong();
  }

  @Override
  public synchronized String status() {
    OperatingSystemMXBean system = this.system;
    if (system == null) {
      return "";
    }
    List<String> details = new ArrayList<>();
    // -1 when not supported
    long cpuNanos = system.getProcessCpuTime();
    long nanos = nanoTime.getAsLong();
    long elapsedNanos = nanos - lastNanos;
    int processors = system.getAvailableProcessors();
    if (cpuNanos >= 0 && lastCpuNanos >= 0 && elapsedNanos > 0 && processors > 0) {
      double share = Math.min(100.0, 100.0 * (cpuNanos - lastCpuNanos) / elapsedNanos / processors);
      details.add(String.format(Locale.ROOT, "%.1f%% process", Math.max(0.0, share)));
    }
    lastCpuNanos = cpuNanos;
    lastNanos = nanos;
    // negative when not available
    double systemLoad = system.getCpuLoad();
    if (systemLoad >= 0) {
      details.add(String.format(Locale.ROOT, "%.1f%% system", 100.0 * systemLoad));
    }
    double loadAverage = system.getSystemLoadAverage();
    if (loadAverage >= 0) {
      details.add(String.format(Locale.ROOT, "load %.2f", loadAverage));
    }
    return details.isEmpty() ? "" : "CPU: " + String.join("; ", details);
  }
}
