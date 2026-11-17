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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link NotifySocketAddress}.
 *
 * @author Joao Silva
 */
class NotifySocketAddressTest {

  @Test
  void fileSystemSocketEndsWithNul() {
    byte[] sockaddr = NotifySocketAddress.sockaddr("/run/systemd/notify");

    assertThat(family(sockaddr)).isEqualTo((short) 1);
    assertThat(path(sockaddr)).isEqualTo("/run/systemd/notify\0".getBytes(UTF_8));
  }

  @Test
  void abstractSocketStartsWithNulInsteadOfAt() {
    byte[] sockaddr = NotifySocketAddress.sockaddr("@/org/freedesktop/systemd1/notify/123");

    assertThat(family(sockaddr)).isEqualTo((short) 1);
    // without a trailing NUL: every byte of an abstract name is significant
    assertThat(path(sockaddr)).isEqualTo("\0/org/freedesktop/systemd1/notify/123".getBytes(UTF_8));
  }

  @Test
  void acceptsTheLongestPath() {
    // 107 bytes, and the trailing NUL
    String address = "/" + "a".repeat(106);

    assertThat(NotifySocketAddress.sockaddr(address)).hasSize(2 + 108);
  }

  @ParameterizedTest
  @ValueSource(strings = {"@", "/"})
  void rejectsLongerAddresses(String prefix) {
    // 109 bytes, which do not fit in sun_path, with or without the trailing NUL
    String address = prefix + "a".repeat(108);

    assertThatThrownBy(() -> NotifySocketAddress.sockaddr(address)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than 108");
  }

  @ParameterizedTest
  @ValueSource(strings = {"vsock:2:1234", "relative/path"})
  void rejectsUnsupportedAddresses(String address) {
    assertThatThrownBy(() -> NotifySocketAddress.sockaddr(address)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only Unix domain sockets are supported");
  }

  private static short family(byte[] sockaddr) {
    return ByteBuffer.wrap(sockaddr).order(ByteOrder.nativeOrder()).getShort();
  }

  private static byte[] path(byte[] sockaddr) {
    return Arrays.copyOfRange(sockaddr, 2, sockaddr.length);
  }
}
