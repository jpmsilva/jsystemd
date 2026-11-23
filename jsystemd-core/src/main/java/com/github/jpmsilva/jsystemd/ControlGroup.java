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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The control group of the process, for the status providers that read its interface files, such as {@code memory.current}. Not meant to be used directly.
 *
 * <p>Only the unified hierarchy of control groups (cgroup v2) is supported, the one systemd uses on current Linux distributions, as the legacy one is
 * deprecated.
 *
 * @author Joao Silva
 */
final class ControlGroup {

  static final Path PROC_SELF_CGROUP = Path.of("/proc/self/cgroup");

  static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

  private final Path root;

  private final Path directory;

  private ControlGroup(Path root, Path directory) {
    this.root = root;
    this.directory = directory;
  }

  /**
   * Finds the control group the process is in.
   *
   * @param procSelfCgroup the file that lists the control groups of the process, {@code /proc/self/cgroup}
   * @param root the directory the unified hierarchy is mounted on, {@code /sys/fs/cgroup}
   * @param file an interface file of the controller the caller reads, such as {@code memory.current}, which the control group must have
   * @return the control group of the process, or null if it is not in the unified hierarchy, if the controller is not enabled for it, or when not
   *     running on Linux
   */
  static @Nullable ControlGroup find(Path procSelfCgroup, Path root, String file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(procSelfCgroup, StandardCharsets.UTF_8);
    } catch (IOException | SecurityException e) {
      return null;
    }
    Path normalizedRoot = root.normalize();
    // the unified hierarchy is the one with id 0 and no controllers, such as "0::/system.slice/myservice.service"
    for (String line : lines) {
      if (line.startsWith("0::/")) {
        Path directory = normalizedRoot.resolve(line.substring("0::/".length())).normalize();
        // the controller may not be enabled for the group, or the unified hierarchy may only be mounted elsewhere, as in the hybrid layout
        return directory.startsWith(normalizedRoot) && Files.isReadable(directory.resolve(file)) ? new ControlGroup(normalizedRoot, directory) : null;
      }
    }
    return null;
  }

  /**
   * Returns the directory of the control group.
   *
   * @return the directory of the control group
   */
  Path directory() {
    return directory;
  }

  /**
   * Reads an interface file of the control group.
   *
   * @param file the name of the file, such as {@code memory.current}
   * @return the content of the file, without the line break at its end
   * @throws IOException if the file cannot be read, such as when the control group is gone, as when the process was moved to another one
   */
  String read(String file) throws IOException {
    return read(directory.resolve(file));
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8).trim();
  }

  /**
   * Returns the lowest limit of the control group and of its parents, as each of them limits all its children, or -1 if there is none.
   *
   * <p>The root of the hierarchy is included: the actual root group has no limits, and no files for them either, but in a container with a control
   * group namespace of its own, as by default with Docker and Podman, the root seen from the container is the control group of the container, which has the
   * limits of the container. The process is then in the root, as {@code 0::/}, or in one of its children when systemd runs in the container.
   *
   * @param file the name of the file of the limit, such as {@code memory.max}, which holds {@code max} when there is no limit
   * @return the lowest limit, or -1 if there is none
   * @throws IOException if a file of the limit cannot be read
   * @throws NumberFormatException if a limit is not a number
   */
  long limit(String file) throws IOException {
    long limit = -1;
    for (Path group = directory; group != null && group.startsWith(root); group = group.getParent()) {
      Path limitFile = group.resolve(file);
      if (Files.isReadable(limitFile)) {
        String value = read(limitFile);
        if (!"max".equals(value)) {
          long groupLimit = Long.parseLong(value);
          limit = limit < 0 ? groupLimit : Math.min(limit, groupLimit);
        }
      }
    }
    return limit;
  }
}
