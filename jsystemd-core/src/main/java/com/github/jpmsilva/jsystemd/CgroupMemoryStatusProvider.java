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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  private static final Path PROC_SELF_CGROUP = Path.of("/proc/self/cgroup");

  private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

  private final Path root;

  // the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  private final Path cgroup;

  /**
   * Create a new CgroupMemoryStatusProvider, for the control group the process is in when created.
   */
  public CgroupMemoryStatusProvider() {
    this(PROC_SELF_CGROUP, CGROUP_ROOT);
  }

  CgroupMemoryStatusProvider(Path procSelfCgroup, Path root) {
    this.root = root.normalize();
    this.cgroup = cgroup(procSelfCgroup, this.root);
  }

  // for tests: the directory of the control group of the process, or null if it is not in the unified hierarchy
  @Nullable
  Path cgroup() {
    return cgroup;
  }

  private static @Nullable Path cgroup(Path procSelfCgroup, Path root) {
    List<String> lines;
    try {
      lines = Files.readAllLines(procSelfCgroup, StandardCharsets.UTF_8);
    } catch (IOException | SecurityException e) {
      return null;
    }
    // the unified hierarchy is the one with id 0 and no controllers, such as "0::/system.slice/myservice.service"
    for (String line : lines) {
      if (line.startsWith("0::/")) {
        Path cgroup = root.resolve(line.substring("0::/".length())).normalize();
        // the memory controller may not be enabled for the group, or the unified hierarchy may only be mounted elsewhere, as in the hybrid layout
        return cgroup.startsWith(root) && Files.isReadable(cgroup.resolve("memory.current")) ? cgroup : null;
      }
    }
    return null;
  }

  @Override
  public String status() {
    Path cgroup = this.cgroup;
    if (cgroup == null) {
      return "";
    }
    try {
      long current = Long.parseLong(read(cgroup.resolve("memory.current")));
      long limit = limit(cgroup);
      return limit < 0 ? String.format(Locale.ROOT, "Cgroup memory: %s", formatByteCount(current))
          : String.format(Locale.ROOT, "Cgroup memory: %s/%s", formatByteCount(current), formatByteCount(limit));
    } catch (IOException | RuntimeException e) {
      // the control group may be gone, such as when the process was moved to another one
      return "";
    }
  }

  /**
   * Returns the lowest limit of the control group and of its parents, as each of them limits the memory of all its children, or -1 if there is none.
   *
   * <p>The root of the hierarchy is included: the actual root group has no limit, and no {@code memory.max} file either, but in a container with a control
   * group namespace of its own, as by default with Docker and Podman, the root seen from the container is the control group of the container, which has the
   * limit of the container. The process is then in the root, as {@code 0::/}, or in one of its children when systemd runs in the container.
   */
  private long limit(Path cgroup) throws IOException {
    long limit = -1;
    for (Path group = cgroup; group != null && group.startsWith(root); group = group.getParent()) {
      Path file = group.resolve("memory.max");
      if (Files.isReadable(file)) {
        String value = read(file);
        if (!"max".equals(value)) {
          long groupLimit = Long.parseLong(value);
          limit = limit < 0 ? groupLimit : Math.min(limit, groupLimit);
        }
      }
    }
    return limit;
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8).trim();
  }
}
