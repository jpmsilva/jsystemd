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
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Tests for {@link NotifyChannel} on Java 22 and later, with a {@code vsock:} address, which it leaves to libsystemd, through JNA. Run by failsafe, on the
 * multi-release JAR, with {@code NOTIFY_SOCKET} set by the build to an address of the local machine that nothing listens on. Only on Linux, as the
 * notifications are only set up there.
 *
 * @author Joao Silva
 */
@EnabledOnOs(OS.LINUX)
class NotifyChannelVsockIT {

  @Test
  void leavesOtherAddressesToLibsystemd() {
    if (NotifyChannel.initialized()) {
      assertThat(NotifyChannel.implementation()).isEqualTo(NotifyChannel.LIBSYSTEMD);
      assertThat(NotifyChannel.failure()).isNull();
      // as with any address, libsystemd only reports that nothing listens to its caller, where it is ignored
      assertThatCode(SystemdNotify::ready).doesNotThrowAnyException();
    } else {
      // where libsystemd is not installed: the address itself is not rejected, and the socket is not tried
      assertThat(NotifyChannel.failure()).isInstanceOf(UnsatisfiedLinkError.class).hasNoSuppressedExceptions();
    }
  }
}
