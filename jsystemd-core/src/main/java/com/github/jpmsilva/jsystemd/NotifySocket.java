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

import org.jspecify.annotations.Nullable;

/**
 * Sends the notifications to a Unix domain socket itself, without libsystemd. Not on Java 17 to 21, as the JDK cannot send datagrams over Unix domain
 * sockets (<a href="https://bugs.openjdk.org/browse/JDK-8297837">JDK-8297837</a>): this version only tells {@link NotifyChannel} to use libsystemd instead.
 *
 * <p>On Java 22 and later, the JAR has another version of this class, under {@code META-INF/versions/22}, which sends the notifications with the Foreign
 * Function and Memory API. Both versions must keep the same methods.
 *
 * @author Joao Silva
 */
final class NotifySocket {

  private NotifySocket() {
  }

  /**
   * Sets up the socket for a Unix domain socket address.
   *
   * @param notifySocket the contents of the environment variable {@code NOTIFY_SOCKET}, a Unix domain socket
   * @return {@code null}, so that libsystemd is used instead
   */
  @SuppressWarnings("unused")
  static @Nullable NotifySocket create(String notifySocket) {
    return null;
  }

  /**
   * Sends a message. Never called, as no instance is ever created.
   *
   * @param message the message
   * @return why the message could not be sent, or {@code null} if it was sent
   */
  @Nullable String send(String message) {
    throw new UnsupportedOperationException("Unix domain datagram sockets need Java 22 or later");
  }
}
