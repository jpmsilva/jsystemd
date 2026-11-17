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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.management.OperatingSystemMXBean;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Tests for {@link CpuStatusProvider}.
 *
 * @author Joao Silva
 */
class CpuStatusProviderTest {

  private final AtomicLong nanoTime = new AtomicLong();

  @Test
  @EnabledOnOs(OS.LINUX)
  void reportsTheCpuUsageOfThisJvmRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(new CpuStatusProvider().status()).matches("CPU: [0-9]+\\.[0-9]% process; [0-9]+\\.[0-9]% system; load [0-9]+\\.[0-9]{2}");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void reportsTheShareOfTheProcessorsUsedSinceTheLastRead() {
    OperatingSystemMXBean system = system();
    CpuStatusProvider provider = new CpuStatusProvider(system, nanoTime::get);

    // 1 second of CPU time over 2 seconds on 4 processors
    when(system.getProcessCpuTime()).thenReturn(TimeUnit.SECONDS.toNanos(11));
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(2));
    assertThat(provider.status()).isEqualTo("CPU: 12.5% process; 40.1% system; load 1.20");

    // 2 more seconds of CPU time over 1 second
    when(system.getProcessCpuTime()).thenReturn(TimeUnit.SECONDS.toNanos(13));
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertThat(provider.status()).isEqualTo("CPU: 50.0% process; 40.1% system; load 1.20");
  }

  @Test
  void leavesOutWhatIsNotAvailable() {
    OperatingSystemMXBean system = system();
    when(system.getCpuLoad()).thenReturn(-1.0);
    when(system.getSystemLoadAverage()).thenReturn(-1.0);
    CpuStatusProvider provider = new CpuStatusProvider(system, nanoTime::get);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEqualTo("CPU: 0.0% process");

    when(system.getProcessCpuTime()).thenReturn(-1L);
    nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsNothingWithoutTheOperatingSystemBean() {
    assertThat(new CpuStatusProvider(null, nanoTime::get).status()).isEmpty();
  }

  private static OperatingSystemMXBean system() {
    OperatingSystemMXBean system = mock(OperatingSystemMXBean.class);
    when(system.getProcessCpuTime()).thenReturn(TimeUnit.SECONDS.toNanos(10));
    when(system.getAvailableProcessors()).thenReturn(4);
    when(system.getCpuLoad()).thenReturn(0.401);
    when(system.getSystemLoadAverage()).thenReturn(1.2);
    return system;
  }
}
