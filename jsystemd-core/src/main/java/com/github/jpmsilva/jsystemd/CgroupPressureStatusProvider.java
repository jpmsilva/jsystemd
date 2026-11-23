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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the pressure the control group of the process is under, such as
 * {@code Cgroup pressure: 2.1% cpu; 0.0% memory; 12.3% io; 40.0% throttled}: how much the service is held back by a lack of CPU, memory or I/O, which
 * neither its CPU usage nor its memory usage tell.
 *
 * <p>The pressure of each resource is the share of the last 10 seconds in which at least one task of the service was stalled waiting for it, as the
 * kernel measures it (pressure stall information): runnable but waiting for a CPU, reclaiming memory or waiting for swapped pages, or waiting for I/O. A
 * service with as many busy threads as CPUs is under no CPU pressure, while one with more is. Memory pressure that keeps growing means the service is about
 * to reach its {@code MemoryHigh} or {@code MemoryMax}, even though its heap may still have room.
 *
 * <p>The throttling is the share of the enforcement periods of the CPU quota of the service, since the status was last read, in which the service used up
 * its quota, and was kept from running until the next one. It is only reported when the service has a quota, set by systemd with {@code CPUQuota} in the
 * service unit. A throttled service is slow while the system has idle CPUs, which shows as latency rather than as CPU usage.
 *
 * <p>Each part is left out when the kernel does not report it: the pressure requires a kernel with pressure stall information enabled, and is not
 * accounted for the root control group. Only the unified hierarchy of control groups (cgroup v2) is supported, the one systemd uses on current Linux
 * distributions, as the legacy one is deprecated. Nothing is reported otherwise, or when not running on Linux.
 *
 * @author Joao Silva
 */
public class CgroupPressureStatusProvider implements StatusProvider {

  private static final List<String> RESOURCES = List.of("cpu", "memory", "io");

  // the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  private final ControlGroup cgroup;

  // the enforcement periods of the CPU quota, and those in which the control group was throttled, when the status was last read, or -1 if they were not
  // reported; guarded by this
  private long lastPeriods;
  private long lastThrottled;

  /**
   * Create a new CgroupPressureStatusProvider, for the control group the process is in when created, which reports the throttling since it was created
   * on the first read.
   */
  public CgroupPressureStatusProvider() {
    this(ControlGroup.PROC_SELF_CGROUP, ControlGroup.CGROUP_ROOT);
  }

  CgroupPressureStatusProvider(Path procSelfCgroup, Path root) {
    // every control group of the unified hierarchy has this file, whichever controllers are enabled for it
    this.cgroup = ControlGroup.find(procSelfCgroup, root, "cgroup.controllers");
    long[] throttling = cgroup == null ? null : throttling(cgroup);
    this.lastPeriods = throttling == null ? -1 : throttling[0];
    this.lastThrottled = throttling == null ? -1 : throttling[1];
  }

  @Override
  public synchronized String status() {
    ControlGroup cgroup = this.cgroup;
    if (cgroup == null) {
      return "";
    }
    // the statuses of all providers are joined with commas, so the details are separated otherwise
    List<String> details = new ArrayList<>();
    for (String resource : RESOURCES) {
      double pressure = pressure(cgroup, resource);
      if (pressure >= 0) {
        details.add(String.format(Locale.ROOT, "%.1f%% %s", pressure, resource));
      }
    }
    long[] throttling = throttling(cgroup);
    if (throttling != null) {
      // the counters only grow, but the process may be moved to another control group, so the differences are kept positive
      long periods = throttling[0] - lastPeriods;
      long throttled = Math.max(0, throttling[1] - lastThrottled);
      // without a quota, there are no enforcement periods
      if (lastPeriods >= 0 && periods > 0) {
        details.add(String.format(Locale.ROOT, "%.1f%% throttled", Math.min(100.0, 100.0 * throttled / periods)));
      }
      lastPeriods = throttling[0];
      lastThrottled = throttling[1];
    } else {
      lastPeriods = -1;
      lastThrottled = -1;
    }
    return details.isEmpty() ? "" : "Cgroup pressure: " + String.join("; ", details);
  }

  // the share of the last 10 seconds in which some task was stalled on the resource, as a percentage, or -1 if it is not reported
  private static double pressure(ControlGroup cgroup, String resource) {
    try {
      // such as "some avg10=0.18 avg60=0.03 avg300=0.00 total=299040577", followed by the same for "full"
      for (String line : cgroup.read(resource + ".pressure").split("\n")) {
        if (line.startsWith("some ")) {
          for (String field : line.split(" ")) {
            if (field.startsWith("avg10=")) {
              return Double.parseDouble(field.substring("avg10=".length()));
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // no pressure stall information: not enabled in the kernel, turned off for the control group, or the control group is gone
      return -1;
    }
    return -1;
  }

  // the enforcement periods of the CPU quota so far, and those in which the control group was throttled, or null if they are not reported
  private static long @Nullable [] throttling(ControlGroup cgroup) {
    long periods = -1;
    long throttled = -1;
    try {
      // such as "nr_periods 1200", among other counters; only there when the CPU controller is enabled for the control group
      for (String line : cgroup.read("cpu.stat").split("\n")) {
        if (line.startsWith("nr_periods ")) {
          periods = Long.parseLong(line.substring("nr_periods ".length()).trim());
        } else if (line.startsWith("nr_throttled ")) {
          throttled = Long.parseLong(line.substring("nr_throttled ".length()).trim());
        }
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
    return periods < 0 || throttled < 0 ? null : new long[] {periods, throttled};
  }
}
