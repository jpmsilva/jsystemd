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

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Provides information regarding the file descriptors of the process: as a {@link StatusProvider}, the open file descriptors out of the most the
 * process can open, such as {@code File descriptors: 812/65536}, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as
 * unhealthy when they are about to run out, if enabled.
 *
 * <p>Every open file, socket and pipe takes a file descriptor, and once they run out, the application can no longer accept connections or open files.
 * As a leak of file descriptors never resolves by itself, restarting the application is usually the only way out. The limit is the one systemd sets with
 * {@code LimitNOFILE} in the service unit.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while the open file descriptors are at or above the given share of the limit, reporting both in the health details, under
 * {@code openFileDescriptors} and {@code maxFileDescriptors}. As a burst of connections can also open many file descriptors for a short while, the
 * unhealthy state can be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}.
 *
 * <p>The file descriptors are only known on Unix-like systems, and nothing is reported otherwise.
 *
 * @author Joao Silva
 */
public class FileDescriptorStatusProvider extends DelayableHealthProvider implements StatusProvider {

  @Nullable
  private final UnixOperatingSystemMXBean system;

  private final boolean health;

  private final double threshold;

  /**
   * Create a new FileDescriptorStatusProvider.
   *
   * @param health <code>true</code> to report the application as unhealthy when the file descriptors are about to run out
   * @param threshold the share of the limit of file descriptors from which the application is unhealthy, such as <code>0.9</code> for 90%
   * @throws IllegalArgumentException if the threshold is not greater than 0 and at most 1
   */
  public FileDescriptorStatusProvider(boolean health, double threshold) {
    this(ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean system ? system : null, health, threshold);
  }

  FileDescriptorStatusProvider(@Nullable UnixOperatingSystemMXBean system, boolean health, double threshold) {
    if (!(threshold > 0 && threshold <= 1)) {
      throw new IllegalArgumentException("Threshold must be greater than 0 and at most 1");
    }
    this.system = system;
    this.health = health;
    this.threshold = threshold;
  }

  // for tests: whether the file descriptors about to run out report the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  // for tests: the share of the limit from which the application is unhealthy
  double threshold() {
    return threshold;
  }

  @Override
  public String status() {
    UnixOperatingSystemMXBean system = this.system;
    if (system == null) {
      return "";
    }
    return String.format(Locale.ROOT, "File descriptors: %d/%d", system.getOpenFileDescriptorCount(), system.getMaxFileDescriptorCount());
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while the open file descriptors are at or above the threshold, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    UnixOperatingSystemMXBean system = this.system;
    if (system == null || !health) {
      return Health.HEALTHY;
    }
    long open = system.getOpenFileDescriptorCount();
    long max = system.getMaxFileDescriptorCount();
    if (max <= 0 || open < threshold * max) {
      return Health.HEALTHY;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("openFileDescriptors", open);
    details.put("maxFileDescriptors", max);
    return new Health(false, details);
  }
}
