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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CgroupPressureStatusProvider}, with a fake control group file system.
 *
 * @author Joao Silva
 */
class CgroupPressureStatusProviderTest {

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

  private Path group(String... files) throws IOException {
    Path group = root().resolve(SERVICE);
    Files.createDirectories(group);
    // every control group of the unified hierarchy has it
    Files.writeString(group.resolve("cgroup.controllers"), "cpu io memory pids\n");
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(group.resolve(files[i]), files[i + 1] + "\n");
    }
    return group;
  }

  private CgroupPressureStatusProvider provider() throws IOException {
    return new CgroupPressureStatusProvider(procSelfCgroup("0::/" + SERVICE + "\n"), root());
  }

  private static String pressure(String some, String full) {
    return "some avg10=" + some + " avg60=0.03 avg300=0.00 total=299040577\nfull avg10=" + full + " avg60=0.00 avg300=0.00 total=128905320";
  }

  private static String cpuStat(long periods, long throttled) {
    return "usage_usec 54321\nuser_usec 4321\nsystem_usec 1234\nnr_periods " + periods + "\nnr_throttled " + throttled + "\nthrottled_usec 99";
  }

  @Test
  void reportsThePressureOfEachResource() throws IOException {
    group("cpu.pressure", pressure("2.10", "0.00"), "memory.pressure", pressure("0.00", "0.00"), "io.pressure", pressure("12.34", "9.99"));

    // the share of the time in which some task was stalled, rather than all of them
    assertThat(provider().status()).isEqualTo("Cgroup pressure: 2.1% cpu; 0.0% memory; 12.3% io");
  }

  @Test
  void reportsThePressureRegardlessOfDefaultLocale() throws IOException {
    group("cpu.pressure", pressure("2.10", "0.00"));
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(provider().status()).isEqualTo("Cgroup pressure: 2.1% cpu");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void leavesOutTheResourcesWhosePressureIsNotReported() throws IOException {
    // as when the kernel accounts no pressure for a resource, or when its file cannot be parsed
    group("cpu.pressure", pressure("2.10", "0.00"), "io.pressure", "unexpected");

    assertThat(provider().status()).isEqualTo("Cgroup pressure: 2.1% cpu");
  }

  @Test
  void reportsTheThrottlingSinceCreatedOnTheFirstRead() throws IOException {
    Path group = group("cpu.pressure", pressure("50.00", "0.00"), "cpu.stat", cpuStat(1000, 100));
    CgroupPressureStatusProvider provider = provider();

    // 200 more periods, 80 of which throttled
    Files.writeString(group.resolve("cpu.stat"), cpuStat(1200, 180));

    assertThat(provider.status()).isEqualTo("Cgroup pressure: 50.0% cpu; 40.0% throttled");
  }

  @Test
  void reportsTheThrottlingSinceTheLastRead() throws IOException {
    Path group = group("cpu.stat", cpuStat(1000, 100));
    CgroupPressureStatusProvider provider = provider();
    Files.writeString(group.resolve("cpu.stat"), cpuStat(1200, 180));
    provider.status();

    Files.writeString(group.resolve("cpu.stat"), cpuStat(1300, 180));

    assertThat(provider.status()).isEqualTo("Cgroup pressure: 0.0% throttled");
  }

  @Test
  void leavesOutTheThrottlingWithoutQuota() throws IOException {
    // without a quota there are no enforcement periods, and without the CPU controller, no counters for them at all
    Path group = group("cpu.pressure", pressure("2.10", "0.00"), "cpu.stat", cpuStat(0, 0));
    CgroupPressureStatusProvider provider = provider();

    assertThat(provider.status()).isEqualTo("Cgroup pressure: 2.1% cpu");

    Files.writeString(group.resolve("cpu.stat"), "usage_usec 54321\nuser_usec 4321\nsystem_usec 1234\n");

    assertThat(provider.status()).isEqualTo("Cgroup pressure: 2.1% cpu");
  }

  @Test
  void reportsTheThrottlingOnlyOnceTheCountersWereReadTwice() throws IOException {
    // a quota set after the provider was created: the first counters are only the reference for the next read
    Path group = group("cpu.stat", "usage_usec 54321");
    CgroupPressureStatusProvider provider = provider();
    Files.writeString(group.resolve("cpu.stat"), cpuStat(100, 50));

    assertThat(provider.status()).isEmpty();

    Files.writeString(group.resolve("cpu.stat"), cpuStat(200, 75));

    assertThat(provider.status()).isEqualTo("Cgroup pressure: 25.0% throttled");
  }

  @Test
  void reportsNothingWhenNothingIsReported() throws IOException {
    group();

    assertThat(provider().status()).isEmpty();
  }

  @Test
  void reportsNothingWithoutTheUnifiedHierarchy() throws IOException {
    group("cpu.pressure", pressure("2.10", "0.00"));

    assertThat(new CgroupPressureStatusProvider(procSelfCgroup("12:memory:/" + SERVICE + "\n"), root()).status()).isEmpty();
  }

  @Test
  void reportsNothingWhenNotOnLinux() {
    assertThat(new CgroupPressureStatusProvider(dir.resolve("missing"), root()).status()).isEmpty();
  }
}
