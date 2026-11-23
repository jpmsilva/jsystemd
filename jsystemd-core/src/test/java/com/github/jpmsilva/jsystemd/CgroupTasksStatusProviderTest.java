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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CgroupTasksStatusProvider}, with a fake control group file system.
 *
 * @author Joao Silva
 */
class CgroupTasksStatusProviderTest {

  private static final String SERVICE = "system.slice/myservice.service";

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

  private CgroupTasksStatusProvider provider(boolean health, double threshold) throws IOException {
    return new CgroupTasksStatusProvider(procSelfCgroup("0::/" + SERVICE + "\n"), root(), health, threshold);
  }

  @Test
  void reportsTheTasksOfTheControlGroupOutOfItsLimit() throws IOException {
    group(SERVICE, "pids.current", "312", "pids.max", "4915");

    assertThat(provider(false, 0.9).status()).isEqualTo("Cgroup tasks: 312/4915");
  }

  @Test
  void reportsTheTasksWithoutLimitWhenThereIsNone() throws IOException {
    group(SERVICE, "pids.current", "312", "pids.max", "max");

    assertThat(provider(false, 0.9).status()).isEqualTo("Cgroup tasks: 312");
  }

  @Test
  void reportsTheLowestLimitOfTheControlGroupAndItsParents() throws IOException {
    group("system.slice", "pids.max", "1000");
    group(SERVICE, "pids.current", "312", "pids.max", "4915");

    assertThat(provider(false, 0.9).status()).isEqualTo("Cgroup tasks: 312/1000");
  }

  @Test
  void isUnhealthyFromTheThreshold() throws IOException {
    group(SERVICE, "pids.current", "900", "pids.max", "1000");

    HealthProvider.Health health = provider(true, 0.9).health();

    assertThat(health.healthy()).isFalse();
    // more threads may still finish, so the application may recover
    assertThat(health.unrecoverable()).isFalse();
    assertThat(health.details()).containsExactly(entry("tasks", 900L), entry("maxTasks", 1000L));
  }

  @Test
  void isHealthyBelowTheThreshold() throws IOException {
    group(SERVICE, "pids.current", "899", "pids.max", "1000");

    assertThat(provider(true, 0.9).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() throws IOException {
    group(SERVICE, "pids.current", "1000", "pids.max", "1000");

    assertThat(provider(false, 0.9).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWithoutLimit() throws IOException {
    group(SERVICE, "pids.current", "100000", "pids.max", "max");

    assertThat(provider(true, 0.9).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() throws IOException {
    group(SERVICE, "pids.current", "1000", "pids.max", "1000");
    CgroupTasksStatusProvider provider = provider(true, 0.9);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void reportsNothingAndIsHealthyWithoutTheTasksController() throws IOException {
    group(SERVICE, "cgroup.procs", "1");
    CgroupTasksStatusProvider provider = provider(true, 0.9);

    assertThat(provider.status()).isEmpty();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void reportsNothingAndIsHealthyWhenNotOnLinux() {
    CgroupTasksStatusProvider provider = new CgroupTasksStatusProvider(dir.resolve("missing"), root(), true, 0.9);

    assertThat(provider.status()).isEmpty();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void reportsNothingAndIsHealthyOnceTheControlGroupIsGone() throws IOException {
    Path group = group(SERVICE, "pids.current", "1000", "pids.max", "1000");
    CgroupTasksStatusProvider provider = provider(true, 0.9);

    Files.delete(group.resolve("pids.current"));

    assertThat(provider.status()).isEmpty();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -0.5, 1.1, Double.NaN})
  void rejectsThresholdsOutOfRange(double threshold) {
    assertThatThrownBy(() -> new CgroupTasksStatusProvider(false, threshold)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Threshold");
  }
}
