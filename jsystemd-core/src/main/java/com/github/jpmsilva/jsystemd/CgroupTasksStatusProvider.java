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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Provides information regarding the tasks of the control group of the process: as a {@link StatusProvider}, the tasks of the control group out of the
 * most it can have, such as {@code Cgroup tasks: 312/4915}, or without the limit when there is none, such as {@code Cgroup tasks: 312}, and as a
 * {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy when they are about to run out, if enabled.
 *
 * <p>Every thread of every process of the service is a task, so the JVM takes one for each of its platform threads, including its own, such as those of
 * the garbage collector. Once they run out, no thread can be created, which often leaves the application running, but unable to work. The limit is the
 * one systemd sets with {@code TasksMax} in the service unit, or in any of the slices it belongs to, whichever is lowest.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while the tasks are at or above the given share of the limit, reporting both in the health details, under {@code tasks} and {@code maxTasks}.
 * As a burst of work can also create many threads for a short while, the unhealthy state can be delayed, with
 * {@link #setUnhealthyDelay(java.time.Duration)}. Without a limit, the application is always healthy.
 *
 * <p>Only the unified hierarchy of control groups (cgroup v2) is supported, the one systemd uses on current Linux distributions, as the legacy one is
 * deprecated. Nothing is reported otherwise, or when not running on Linux.
 *
 * @author Joao Silva
 */
public class CgroupTasksStatusProvider extends DelayableHealthProvider implements StatusProvider {

  // the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  private final ControlGroup cgroup;

  private final boolean health;

  private final double threshold;

  /**
   * Create a new CgroupTasksStatusProvider, for the control group the process is in when created.
   *
   * @param health <code>true</code> to report the application as unhealthy when the tasks are about to run out
   * @param threshold the share of the limit of tasks from which the application is unhealthy, such as <code>0.9</code> for 90%
   * @throws IllegalArgumentException if the threshold is not greater than 0 and at most 1
   */
  public CgroupTasksStatusProvider(boolean health, double threshold) {
    this(ControlGroup.PROC_SELF_CGROUP, ControlGroup.CGROUP_ROOT, health, threshold);
  }

  CgroupTasksStatusProvider(Path procSelfCgroup, Path root, boolean health, double threshold) {
    if (!(threshold > 0 && threshold <= 1)) {
      throw new IllegalArgumentException("Threshold must be greater than 0 and at most 1");
    }
    this.cgroup = ControlGroup.find(procSelfCgroup, root, "pids.current");
    this.health = health;
    this.threshold = threshold;
  }

  // for tests: whether the tasks about to run out report the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  // for tests: the share of the limit from which the application is unhealthy
  double threshold() {
    return threshold;
  }

  @Override
  public String status() {
    ControlGroup cgroup = this.cgroup;
    if (cgroup == null) {
      return "";
    }
    try {
      long current = Long.parseLong(cgroup.read("pids.current"));
      long limit = cgroup.limit("pids.max");
      return limit < 0 ? String.format(Locale.ROOT, "Cgroup tasks: %d", current) : String.format(Locale.ROOT, "Cgroup tasks: %d/%d", current, limit);
    } catch (IOException | RuntimeException e) {
      // the control group may be gone, such as when the process was moved to another one
      return "";
    }
  }

  /**
   * Provides the health of the application, without the delay: unhealthy while the tasks are at or above the threshold, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    ControlGroup cgroup = this.cgroup;
    if (cgroup == null || !health) {
      return Health.HEALTHY;
    }
    long current;
    long limit;
    try {
      current = Long.parseLong(cgroup.read("pids.current"));
      limit = cgroup.limit("pids.max");
    } catch (IOException | RuntimeException e) {
      // not knowing the tasks does not make the application unhealthy
      return Health.HEALTHY;
    }
    if (limit <= 0 || current < threshold * limit) {
      return Health.HEALTHY;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("tasks", current);
    details.put("maxTasks", limit);
    return new Health(false, details);
  }
}
