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

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Tests for the {@link NotifySocket} used on Java 22 and later, which sends the notifications with the Foreign Function and Memory API. Run by failsafe, on
 * the multi-release JAR, with {@code NOTIFY_SOCKET} set by the build to a socket in the abstract namespace, which these tests bind before the notifications
 * are set up. Only on Linux, as the notifications are only set up there.
 *
 * @author Joao Silva
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotifyChannelIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static String address;
  private static DatagramReceiver receiver;

  @BeforeAll
  static void bind() throws Throwable {
    address = System.getenv("NOTIFY_SOCKET");
    receiver = DatagramReceiver.bind(address);
  }

  @AfterAll
  static void unbind() throws Exception {
    if (receiver != null) {
      receiver.close();
    }
  }

  @Test
  @Order(1)
  void usesTheSocket() {
    // libsystemd would be used if the JAR were not multi-release
    assertThat(NotifyChannel.implementation()).isEqualTo(NotifyChannel.SOCKET);
    assertThat(NotifyChannel.initialized()).isTrue();
    assertThat(NotifyChannel.failure()).isNull();
    assertThat(SystemdNotify.usable()).isTrue();
  }

  @Test
  @Order(2)
  void sendsTheMessage() throws Throwable {
    SystemdNotify.ready();

    assertThat(receiver.receive(TIMEOUT)).contains("READY=1");
    assertThat(NotifyChannel.failing()).isFalse();
  }

  @Test
  @Order(3)
  void sendsTheMessageAsUtf8() throws Throwable {
    NotifyChannel.send("STATUS=café, 100 µs");

    assertThat(receiver.receive(TIMEOUT)).contains("STATUS=café, 100 µs");
  }

  @Test
  @Order(4)
  void sendsFromConcurrentThreads() throws Throwable {
    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      IntStream.range(0, 100).forEach(i -> executor.execute(() -> NotifyChannel.send("STATUS=" + i)));

      // received while they are sent, before the executor is closed, which waits for them: the queue of a datagram socket may only hold a few, 10 by
      // default in a new network namespace, as in a container, and the senders block until there is room, as with libsystemd
      Set<String> received = new HashSet<>();
      for (int i = 0; i < 100; i++) {
        received.add(receiver.receive(TIMEOUT).orElseThrow());
      }
      assertThat(received).hasSize(100).contains("STATUS=0", "STATUS=99");
    }
  }

  @Test
  @Order(5)
  void reportsThatNothingListens() throws Exception {
    receiver.close();
    receiver = null;

    // as libsystemd, which only reports the failure to its caller, where it is ignored
    assertThatCode(SystemdNotify::watchdog).doesNotThrowAnyException();
    assertThat(NotifyChannel.failing()).isTrue();
  }

  @Test
  @Order(6)
  void recoversOnceListenedToAgain() throws Throwable {
    receiver = DatagramReceiver.bind(address);

    SystemdNotify.watchdog();

    assertThat(receiver.receive(TIMEOUT)).contains("WATCHDOG=1");
    assertThat(NotifyChannel.failing()).isFalse();
  }
}
