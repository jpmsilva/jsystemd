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
import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Sends the notifications to a Unix domain socket itself, as libsystemd does, by calling the C library with the Foreign Function and Memory API, so that
 * neither JNA nor libsystemd are needed.
 *
 * <p>This is the version of the class under {@code META-INF/versions/22} in the JAR, used on Java 22 and later. Both versions must keep the same methods.
 *
 * <p>Calling the C library is a restricted operation: unless native access is enabled for the code on the class path, with
 * {@code --enable-native-access=ALL-UNNAMED} or the {@code Enable-Native-Access: ALL-UNNAMED} attribute in the manifest of an executable JAR, the JVM warns
 * when the socket is set up, and refuses it under {@code --illegal-native-access=deny}.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#Notes">the notification protocol</a>
 */
final class NotifySocket {

  private static final int AF_UNIX = 1;
  // the Linux architectures whose socket types have the generic values; MIPS swaps SOCK_DGRAM and SOCK_STREAM, others, such as SPARC, are left to libsystemd
  private static final Set<String> GENERIC_ARCHITECTURES = Set.of("amd64", "x86_64", "x86", "i386", "i686", "aarch64", "arm", "riscv64", "ppc64",
      "ppc64le", "s390x", "loongarch64");
  private static final int SOCK_DGRAM_GENERIC = 2;
  private static final int SOCK_DGRAM_MIPS = 1;
  // O_CLOEXEC, the same on all the architectures above, so that the socket does not leak into processes started from native code, as in libsystemd
  private static final int SOCK_CLOEXEC = 0x80000;

  private static final StructLayout CAPTURED_STATE = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO = CAPTURED_STATE.varHandle(PathElement.groupElement("errno"));

  private final int type;
  private final MethodHandle socket;
  private final MethodHandle sendto;
  private final MethodHandle close;
  private final MethodHandle strerror;
  private final MemorySegment address;

  private NotifySocket(int type, byte[] sockaddr) {
    this.type = type;
    Linker linker = Linker.nativeLinker();
    // size_t and ssize_t (as long) are 64 bits wide on 64-bit platforms only, so the handle is adapted to take and return Java longs on every one
    MemoryLayout size = linker.canonicalLayouts().get("size_t");
    MemoryLayout ssize = linker.canonicalLayouts().get("long");
    Linker.Option errno = Linker.Option.captureCallState("errno");
    socket = downcall(linker, "socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), errno);
    sendto = MethodHandles.explicitCastArguments(
        downcall(linker, "sendto", FunctionDescriptor.of(ssize, JAVA_INT, ADDRESS, size, JAVA_INT, ADDRESS, JAVA_INT), errno),
        MethodType.methodType(long.class, MemorySegment.class, int.class, MemorySegment.class, long.class, int.class, MemorySegment.class, int.class));
    close = downcall(linker, "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    strerror = downcall(linker, "strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    address = Arena.global().allocateFrom(JAVA_BYTE, sockaddr);
  }

  /**
   * Sets up the socket for a Unix domain socket address.
   *
   * @param notifySocket the contents of the environment variable {@code NOTIFY_SOCKET}, a Unix domain socket (see
   *     {@link NotifySocketAddress#isUnixSocket(String)})
   * @return the socket, or {@code null} on an architecture whose socket constants are not known, where libsystemd is used instead
   * @throws RuntimeException if the socket cannot be set up, such as when the address is too long, or native access is denied
   */
  static @Nullable NotifySocket create(String notifySocket) {
    String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    int type;
    if (GENERIC_ARCHITECTURES.contains(architecture)) {
      type = SOCK_DGRAM_GENERIC;
    } else if (architecture.startsWith("mips")) {
      type = SOCK_DGRAM_MIPS;
    } else {
      return null;
    }
    return new NotifySocket(type, NotifySocketAddress.sockaddr(notifySocket));
  }

  private static MethodHandle downcall(Linker linker, String name, FunctionDescriptor descriptor, Linker.Option... options) {
    MemorySegment function = linker.defaultLookup().find(name).orElseThrow(() -> new IllegalStateException("C library function not found: " + name));
    return linker.downcallHandle(function, descriptor, options);
  }

  /**
   * Sends a message, on a socket of its own, as libsystemd does.
   *
   * @param message the message
   * @return why the message could not be sent, or {@code null} if it was sent
   */
  @Nullable String send(String message) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment state = arena.allocate(CAPTURED_STATE);
      int fd = (int) socket.invokeExact(state, AF_UNIX, type | SOCK_CLOEXEC, 0);
      if (fd < 0) {
        return "socket: " + error(state);
      }
      try {
        byte[] bytes = message.getBytes(UTF_8);
        long sent = (long) sendto.invokeExact(state, fd, arena.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length, 0, address, (int) address.byteSize());
        return sent < 0 ? "sendto: " + error(state) : null;
      } finally {
        int unused = (int) close.invokeExact(fd);
      }
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      // the handles declare Throwable, but the C functions do not throw
      throw new IllegalStateException(e);
    }
  }

  private String error(MemorySegment state) throws Throwable {
    int errno = (int) ERRNO.get(state, 0L);
    MemorySegment description = (MemorySegment) strerror.invokeExact(errno);
    return description.reinterpret(Long.MAX_VALUE).getString(0) + " (errno " + errno + ")";
  }
}
