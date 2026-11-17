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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.Optional;

/**
 * A Unix domain datagram socket that receives the notifications in the tests, as systemd does, as the JDK cannot (JDK-8297837). Only for 64-bit Linux.
 *
 * @author Joao Silva
 */
final class DatagramReceiver implements AutoCloseable {

  private static final int AF_UNIX = 1;
  private static final int SOCK_DGRAM = 2;
  private static final int MSG_DONTWAIT = 0x40;

  private static final MethodHandle socket = downcall("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
  private static final MethodHandle bind = downcall("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
  private static final MethodHandle recv = downcall("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
  private static final MethodHandle close = downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

  private final int fd;

  private DatagramReceiver(int fd) {
    this.fd = fd;
  }

  private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
    Linker linker = Linker.nativeLinker();
    return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor);
  }

  /**
   * Binds a socket to an address.
   *
   * @param address the address, in the form of {@code NOTIFY_SOCKET}
   * @return the receiver
   */
  static DatagramReceiver bind(String address) throws Throwable {
    int fd = (int) socket.invokeExact(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0) {
      throw new IllegalStateException("socket failed");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment sockaddr = arena.allocateFrom(JAVA_BYTE, NotifySocketAddress.sockaddr(address));
      if ((int) bind.invokeExact(fd, sockaddr, (int) sockaddr.byteSize()) < 0) {
        int unused = (int) close.invokeExact(fd);
        throw new IllegalStateException("bind failed: " + address);
      }
    }
    return new DatagramReceiver(fd);
  }

  /**
   * Waits for a datagram.
   *
   * @param timeout how long to wait for it
   * @return the datagram, as text, or empty if none arrived in time
   */
  Optional<String> receive(Duration timeout) throws Throwable {
    long deadline = System.nanoTime() + timeout.toNanos();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(4096);
      do {
        long received = (long) recv.invokeExact(fd, buffer, buffer.byteSize(), MSG_DONTWAIT);
        if (received >= 0) {
          return Optional.of(new String(buffer.asSlice(0, received).toArray(JAVA_BYTE), UTF_8));
        }
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
    }
    return Optional.empty();
  }

  @Override
  public void close() throws Exception {
    try {
      int unused = (int) close.invokeExact(fd);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }
}
