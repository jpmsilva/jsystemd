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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests for {@link SystemdNotify}.
 *
 * @author Joao Silva
 */
class SystemdNotifyTest {

  private MockedStatic<NotifyChannel> channel;

  @BeforeEach
  void setUp() {
    // the notifications are sent, but only to the mock
    channel = Mockito.mockStatic(NotifyChannel.class);
    channel.when(NotifyChannel::initialized).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    channel.close();
  }

  @Test
  void sendsTheStatusWholeWhenItFits() {
    String status = "a".repeat(SystemdNotify.STATUS_MAX_BYTES);

    SystemdNotify.status(status);

    channel.verify(() -> NotifyChannel.send("STATUS=" + status));
  }

  @Test
  void cutsTheStatusThatDoesNotFit() {
    SystemdNotify.status("first, " + "x".repeat(SystemdNotify.STATUS_MAX_BYTES));

    // the ellipsis takes 3 bytes
    channel.verify(() -> NotifyChannel.send("STATUS=first, " + "x".repeat(SystemdNotify.STATUS_MAX_BYTES - 7 - 3) + "…"));
  }

  @Test
  void replacesLineBreaksInStatus() {
    // each line of a notification is a separate assignment, so these would otherwise also notify systemd that the application is ready, alive, and stopping
    SystemdNotify.status("db down\nREADY=1, cache down\r\nWATCHDOG=1, first\n\nsecond, queue down\rSTOPPING=1");

    channel.verify(() -> NotifyChannel.send("STATUS=db down READY=1, cache down WATCHDOG=1, first second, queue down STOPPING=1"));
  }

  @Test
  void replacesLineBreaksBeforeCuttingTheStatus() {
    // the line breaks of the status take two bytes, which the space that replaces them halves, so that the status then fits
    String status = "a\r\n" + "b".repeat(SystemdNotify.STATUS_MAX_BYTES - 2);

    SystemdNotify.status(status);

    channel.verify(() -> NotifyChannel.send("STATUS=a " + "b".repeat(SystemdNotify.STATUS_MAX_BYTES - 2)));
  }

  @Test
  void sendsNothingWhenNotUsable() {
    channel.when(NotifyChannel::initialized).thenReturn(false);

    SystemdNotify.status("status");

    channel.verify(() -> NotifyChannel.send(Mockito.anyString()), Mockito.never());
  }

  @Test
  void cutsTheStatusOnlyWhenItDoesNotFit() {
    assertThat(SystemdNotify.cut("status", 6)).isEqualTo("status");
    // the ellipsis takes 3 bytes
    assertThat(SystemdNotify.cut("status", 5)).isEqualTo("st…");
  }

  @Test
  void cutsTheStatusBetweenCharacters() {
    // the euro sign takes 3 bytes, and the emoji 4, as a surrogate pair
    assertThat(SystemdNotify.cut("€€€€", 10)).isEqualTo("€€…");
    assertThat(SystemdNotify.cut("€€€€", 9)).isEqualTo("€€…");
    assertThat(SystemdNotify.cut("€€€€", 8)).isEqualTo("€…");
    assertThat(SystemdNotify.cut("😀".repeat(3), 9)).isEqualTo("😀…");
    assertThat(SystemdNotify.cut("😀".repeat(3), 6)).isEqualTo("…");
  }

  @Test
  void neverCutsTheStatusWithinACharacter() {
    // characters of 1, 2, 3 and 4 bytes in UTF-8, 50 bytes in all
    String text = "aé€😀".repeat(5);

    // every number of bytes too few for the whole text, down to the ellipsis alone
    for (int maxBytes = 3; maxBytes < 50; maxBytes++) {
      String status = SystemdNotify.cut(text, maxBytes);
      byte[] bytes = status.getBytes(UTF_8);

      assertThat(bytes.length).as("bytes of the status cut to %d bytes", maxBytes).isLessThanOrEqualTo(maxBytes);
      assertThat(text).startsWith(status.substring(0, status.length() - 1));
      // a character split between its bytes, or between the halves of a surrogate pair, would not survive the encoding
      assertThat(new String(bytes, UTF_8)).isEqualTo(status);
    }
  }
}
