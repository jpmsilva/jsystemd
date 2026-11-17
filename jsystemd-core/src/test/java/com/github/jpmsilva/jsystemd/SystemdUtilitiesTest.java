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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests for {@link SystemdUtilities}.
 *
 * @author Joao Silva
 */
class SystemdUtilitiesTest {

  @TempDir
  private Path directory;

  @Test
  void detectsLinuxRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under a Turkish locale, the "I" in "LINUX" would be lowercased to a dotless "ı", giving "lınux"
    Locale.setDefault(Locale.forLanguageTag("tr"));
    try (MockedStatic<SystemdUtilities> utilities = Mockito.mockStatic(SystemdUtilities.class, Mockito.CALLS_REAL_METHODS)) {
      utilities.when(SystemdUtilities::osName).thenReturn("LINUX");

      assertThat(SystemdUtilities.isLinux()).isTrue();
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "0, '0.0 B'",
      "1, '1.0 B'",
      "-1, '-1.0 B'",
      // around 1 KiB, the first unit change
      "1023, '1,023.0 B'",
      "1024, '1.0 KiB'",
      "1025, '1.0 KiB'",
      "-1023, '-1,023.0 B'",
      "-1024, '-1.0 KiB'",
      "-1025, '-1.0 KiB'",
      "1536, '1.5 KiB'",
      // approaching each larger unit: the last value rounded down to 1,023.9 in the smaller unit, the first rounded up to 1,024.0, and the last before
      // the larger unit, also shown as 1,024.0, as the unit is chosen before rounding (above PiB, the boundary moves as a double cannot hold every long)
      "1048524, '1,023.9 KiB'",
      "1048525, '1,024.0 KiB'",
      "1048575, '1,024.0 KiB'",
      "1073689395, '1,023.9 MiB'",
      "1073689396, '1,024.0 MiB'",
      "1073741823, '1,024.0 MiB'",
      "1099457940684, '1,023.9 GiB'",
      "1099457940685, '1,024.0 GiB'",
      "1099511627775, '1,024.0 GiB'",
      "1125844931261235, '1,023.9 TiB'",
      "1125844931261236, '1,024.0 TiB'",
      "1125899906842623, '1,024.0 TiB'",
      "1152865209611504831, '1,023.9 PiB'",
      "1152865209611504832, '1,024.0 PiB'",
      "1152921504606846975, '1,024.0 PiB'",
      // exactly at, and just above, each larger unit
      "1048576, '1.0 MiB'",
      "1048577, '1.0 MiB'",
      "268435456, '256.0 MiB'",
      "1073741824, '1.0 GiB'",
      "1073741825, '1.0 GiB'",
      "1099511627776, '1.0 TiB'",
      "1099511627777, '1.0 TiB'",
      "1125899906842624, '1.0 PiB'",
      "1125899906842625, '1.0 PiB'",
      "1152921504606846976, '1.0 EiB'",
      "1152921504606846977, '1.0 EiB'",
      "-1536, '-1.5 KiB'",
      // the extremes of long
      "9223372036854775807, '8.0 EiB'",
      "-9223372036854775807, '-8.0 EiB'",
      // has no positive counterpart, so it is formatted as the largest positive value, negated
      "-9223372036854775808, '-8.0 EiB'"
  })
  void formatsByteCountWithBinaryUnitsRegardlessOfDefaultLocale(long bytes, String expected) {
    Locale defaultLocale = Locale.getDefault();
    // a German locale uses a decimal comma and a grouping dot, the opposite of the separators expected
    Locale.setDefault(Locale.GERMANY);
    try {
      assertThat(SystemdUtilities.formatByteCount(bytes)).isEqualTo(expected);
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void rejectsMissingNotifySocket() {
    assertThat(SystemdUtilities.isUsableNotifySocket(null)).isFalse();
    assertThat(SystemdUtilities.isUsableNotifySocket("")).isFalse();
  }

  @Test
  void acceptsExistingFileSystemNotifySocket() throws IOException {
    Path socket = Files.createFile(directory.resolve("notify"));

    assertThat(SystemdUtilities.isUsableNotifySocket(socket.toString())).isTrue();
  }

  @Test
  void rejectsNonExistingFileSystemNotifySocket() {
    assertThat(SystemdUtilities.isUsableNotifySocket(directory.resolve("missing").toString())).isFalse();
  }

  // addresses with no file to check: a socket in the abstract namespace, and a vsock address
  @ParameterizedTest
  @ValueSource(strings = {"@/org/freedesktop/systemd1/notify/123", "vsock:2:1234"})
  void acceptsNotifySocketWithoutFile(String address) {
    assertThat(SystemdUtilities.isUsableNotifySocket(address)).isTrue();
  }
}
