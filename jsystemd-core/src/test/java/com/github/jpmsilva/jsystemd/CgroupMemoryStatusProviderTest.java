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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CgroupMemoryStatusProvider}, with a fake control group file system.
 *
 * @author Joao Silva
 */
class CgroupMemoryStatusProviderTest {

  private static final long MIB = 1024 * 1024;

  @TempDir
  private Path dir;

  private Path root() {
    return dir.resolve("sys/fs/cgroup");
  }

  private Path procSelfCgroup(String content) throws IOException {
    Path file = dir.resolve("proc/self/cgroup");
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  private Path group(String path, String... files) throws IOException {
    Path group = root().resolve(path);
    Files.createDirectories(group);
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(group.resolve(files[i]), files[i + 1] + "\n");
    }
    return group;
  }

  @Test
  void reportsTheMemoryOfTheControlGroupOutOfItsLimit() throws IOException {
    Path group = group("system.slice/myservice.service", "memory.current", String.valueOf(700 * MIB), "memory.max", String.valueOf(1024 * MIB));

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/system.slice/myservice.service\n"), root());

    assertThat(provider.cgroup()).isEqualTo(group);
    assertThat(provider.status()).isEqualTo("Cgroup memory: 700.0 MiB/1.0 GiB");
  }

  @Test
  void reportsTheMemoryWithoutLimitWhenThereIsNone() throws IOException {
    group("system.slice", "memory.max", "max");
    group("system.slice/myservice.service", "memory.current", String.valueOf(700 * MIB), "memory.max", "max");

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/system.slice/myservice.service\n"), root());

    assertThat(provider.status()).isEqualTo("Cgroup memory: 700.0 MiB");
  }

  @Test
  void reportsTheLowestLimitOfTheControlGroupAndItsParents() throws IOException {
    group("app.slice", "memory.max", String.valueOf(512 * MIB));
    group("app.slice/myservice.service", "memory.current", String.valueOf(300 * MIB), "memory.max", String.valueOf(1024 * MIB));

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/app.slice/myservice.service\n"), root());

    assertThat(provider.status()).isEqualTo("Cgroup memory: 300.0 MiB/512.0 MiB");
  }

  @Test
  void reportsTheLimitOfTheContainerInAControlGroupNamespace() throws IOException {
    // the control group of the container, seen from within as the root of the hierarchy, as with Docker and Podman by default
    group("", "memory.current", String.valueOf(100 * MIB), "memory.max", String.valueOf(256 * MIB));

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/\n"), root());

    assertThat(provider.status()).isEqualTo("Cgroup memory: 100.0 MiB/256.0 MiB");
  }

  @Test
  void reportsTheLimitOfTheContainerWhenSystemdRunsInIt() throws IOException {
    // the limit of the container, on the root of the hierarchy of the container, above the control group of the service
    group("", "memory.max", String.valueOf(256 * MIB));
    group("system.slice/myservice.service", "memory.current", String.valueOf(100 * MIB), "memory.max", "max");

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/system.slice/myservice.service\n"), root());

    assertThat(provider.status()).isEqualTo("Cgroup memory: 100.0 MiB/256.0 MiB");
  }

  @Test
  void findsTheUnifiedHierarchyAmongTheLegacyOnes() throws IOException {
    group("system.slice/myservice.service", "memory.current", String.valueOf(MIB), "memory.max", "max");

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(
        procSelfCgroup("12:memory:/system.slice/myservice.service\n1:name=systemd:/system.slice/myservice.service\n0::/system.slice/myservice.service\n"),
        root());

    assertThat(provider.status()).isEqualTo("Cgroup memory: 1.0 MiB");
  }

  @Test
  void reportsNothingWithoutTheUnifiedHierarchy() throws IOException {
    group("memory/system.slice/myservice.service", "memory.usage_in_bytes", String.valueOf(MIB));

    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("12:memory:/system.slice/myservice.service\n"), root());

    assertThat(provider.cgroup()).isNull();
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsNothingWithoutTheMemoryController() throws IOException {
    group("system.slice/myservice.service", "cgroup.procs", "1");

    assertThat(new CgroupMemoryStatusProvider(procSelfCgroup("0::/system.slice/myservice.service\n"), root()).status()).isEmpty();
  }

  @Test
  void reportsNothingWhenNotOnLinux() {
    assertThat(new CgroupMemoryStatusProvider(dir.resolve("missing"), root()).status()).isEmpty();
  }

  @Test
  void reportsNothingOnceTheControlGroupIsGone() throws IOException {
    Path group = group("system.slice/myservice.service", "memory.current", String.valueOf(MIB));
    CgroupMemoryStatusProvider provider = new CgroupMemoryStatusProvider(procSelfCgroup("0::/system.slice/myservice.service\n"), root());

    Files.delete(group.resolve("memory.current"));

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void ignoresControlGroupsOutsideOfTheHierarchy() throws IOException {
    // a readable group out of the hierarchy, which would be reported if the path was not checked
    Path outside = root().resolve("../../etc").normalize();
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("memory.current"), MIB + "\n");

    assertThat(new CgroupMemoryStatusProvider(procSelfCgroup("0::/../../etc\n"), root()).cgroup()).isNull();
  }
}
