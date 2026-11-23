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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the memory used by the control group of the process, out of its limit, such as
 * {@code Cgroup memory: 700.0 MiB/1.0 GiB}, or without the limit when there is none, such as {@code Cgroup memory: 700.0 MiB}.
 *
 * <p>Unlike the memory reported by the other status providers, this is the memory the kernel accounts to the service, and holds it to: all the memory of
 * the process, including the native memory of the JVM, the thread stacks and the page cache. Once it reaches the limit, the kernel kills the process, even
 * though the heap may still have room. The limit is the one systemd sets with {@code MemoryMax} in the service unit, or in any of the slices it belongs
 * to, whichever is lowest.
 *
 * <p>Only the unified hierarchy of control groups (cgroup v2) is supported, the one systemd uses on current Linux distributions, as the legacy one is
 * deprecated. Nothing is reported otherwise, or when not running on Linux.
 *
 * @author Joao Silva
 */
public class CgroupMemoryStatusProvider implements StatusProvider {

  // the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  private final ControlGroup cgroup;

  /**
   * Create a new CgroupMemoryStatusProvider, for the control group the process is in when created.
   */
  public CgroupMemoryStatusProvider() {
    this(ControlGroup.PROC_SELF_CGROUP, ControlGroup.CGROUP_ROOT);
  }

  CgroupMemoryStatusProvider(Path procSelfCgroup, Path root) {
    this.cgroup = ControlGroup.find(procSelfCgroup, root, "memory.current");
  }

  // for tests: the directory of the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  Path cgroup() {
    return cgroup == null ? null : cgroup.directory();
  }

  @Override
  public String status() {
    ControlGroup cgroup = this.cgroup;
    if (cgroup == null) {
      return "";
    }
    try {
      long current = Long.parseLong(cgroup.read("memory.current"));
      // the lowest limit of the control group and of its parents, as each of them limits the memory of all its children
      long limit = cgroup.limit("memory.max");
      return limit < 0 ? String.format(Locale.ROOT, "Cgroup memory: %s", formatByteCount(current))
          : String.format(Locale.ROOT, "Cgroup memory: %s/%s", formatByteCount(current), formatByteCount(limit));
    } catch (IOException | RuntimeException e) {
      // the control group may be gone, such as when the process was moved to another one
      return "";
    }
  }
}
