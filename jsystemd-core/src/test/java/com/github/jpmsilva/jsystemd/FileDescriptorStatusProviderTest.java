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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.management.UnixOperatingSystemMXBean;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link FileDescriptorStatusProvider}.
 *
 * @author Joao Silva
 */
class FileDescriptorStatusProviderTest {

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void reportsTheFileDescriptorsOfThisJvm() {
    assertThat(new FileDescriptorStatusProvider(false, 0.9).status()).matches("File descriptors: [1-9][0-9]*/[1-9][0-9]*");
  }

  @Test
  void reportsTheOpenFileDescriptorsOutOfTheLimit() {
    assertThat(new FileDescriptorStatusProvider(system(812, 65536), false, 0.9).status()).isEqualTo("File descriptors: 812/65536");
  }

  @Test
  void isUnhealthyFromTheThreshold() {
    HealthProvider.Health health = new FileDescriptorStatusProvider(system(900, 1000), true, 0.9).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsExactly(entry("openFileDescriptors", 900L), entry("maxFileDescriptors", 1000L));
  }

  @Test
  void isHealthyBelowTheThreshold() {
    assertThat(new FileDescriptorStatusProvider(system(899, 1000), true, 0.9).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    assertThat(new FileDescriptorStatusProvider(system(1000, 1000), false, 0.9).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    FileDescriptorStatusProvider provider = new FileDescriptorStatusProvider(system(1000, 1000), true, 0.9);
    provider.setUnhealthyDelay(Duration.ofMinutes(1));

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void reportsNothingAndIsHealthyWhereFileDescriptorsAreUnknown() {
    FileDescriptorStatusProvider provider = new FileDescriptorStatusProvider(null, true, 0.9);

    assertThat(provider.status()).isEmpty();
    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -0.5, 1.1, Double.NaN})
  void rejectsThresholdsOutOfRange(double threshold) {
    assertThatThrownBy(() -> new FileDescriptorStatusProvider(false, threshold)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Threshold");
  }

  private static UnixOperatingSystemMXBean system(long open, long max) {
    UnixOperatingSystemMXBean system = mock(UnixOperatingSystemMXBean.class);
    when(system.getOpenFileDescriptorCount()).thenReturn(open);
    when(system.getMaxFileDescriptorCount()).thenReturn(max);
    return system;
  }
}
