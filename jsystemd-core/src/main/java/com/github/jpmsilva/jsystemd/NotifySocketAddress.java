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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Builds the native address of the socket in the environment variable {@code NOTIFY_SOCKET}, for the channel used on Java 22 and later to send the
 * notifications without libsystemd. It is kept apart from that channel, as plain Java, so that it can be tested on every JDK.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#Notes">the notification protocol</a>
 * @see <a href="https://man7.org/linux/man-pages/man7/unix.7.html">unix(7)</a>
 */
final class NotifySocketAddress {

  // struct sockaddr_un: a native sa_family_t, followed by sun_path
  private static final short AF_UNIX = 1;
  private static final int FAMILY_LENGTH = Short.BYTES;
  private static final int PATH_LENGTH = 108;

  private NotifySocketAddress() {
  }

  /**
   * Allows knowing if an address is that of a Unix domain socket, which {@link #sockaddr(String)} supports.
   *
   * @param notifySocket the contents of the environment variable {@code NOTIFY_SOCKET}
   * @return {@code true} for file system sockets (absolute paths) and sockets in the abstract namespace (starting with {@code @})
   */
  static boolean isUnixSocket(String notifySocket) {
    return notifySocket.startsWith("/") || notifySocket.startsWith("@");
  }

  /**
   * Builds the {@code struct sockaddr_un} of a Unix domain socket, as passed to {@code sendto}: only the bytes that matter, so that its length is the
   * length of the address.
   *
   * <p>Like libsystemd, accepts file system sockets (absolute paths), whose path ends with a NUL, and sockets in the abstract namespace (starting with
   * {@code @}), whose name starts with a NUL instead of the {@code @}, and does not end with one. The {@code vsock:} addresses that systemd also supports, for
   * virtual machines, are not supported.
   *
   * @param notifySocket the contents of the environment variable {@code NOTIFY_SOCKET}
   * @return the address
   * @throws IllegalArgumentException if the address is not supported, or too long
   */
  static byte [] sockaddr(String notifySocket) {
    byte[] address = notifySocket.getBytes(UTF_8);
    byte[] path;
    if (notifySocket.startsWith("/")) {
      path = Arrays.copyOf(address, address.length + 1);
    } else if (notifySocket.startsWith("@")) {
      path = address;
      path[0] = 0;
    } else {
      throw new IllegalArgumentException("Unsupported notify socket address, only Unix domain sockets are supported: " + notifySocket);
    }
    if (path.length > PATH_LENGTH) {
      throw new IllegalArgumentException("Notify socket address longer than " + PATH_LENGTH + " bytes: " + notifySocket);
    }
    return ByteBuffer.allocate(FAMILY_LENGTH + path.length).order(ByteOrder.nativeOrder()).putShort(AF_UNIX).put(path).array();
  }
}
