/*
 * Copyright 2018-2026 Joao Silva
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

import static java.lang.invoke.MethodHandles.lookup;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Low level API that sends notifications to systemd, through the socket in the environment variable {@code NOTIFY_SOCKET}. Not meant for direct usage.
 *
 * <p>On Java 17 to 21, the notifications are sent by calling {@code sd_notify} from the native libsystemd library, through JNA. On Java 22 and later, they
 * are sent directly to the socket, with the Foreign Function and Memory API, so that neither JNA nor libsystemd are needed, except for {@code vsock:}
 * addresses, or as a fallback (see {@link NotifyChannel}).
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify</a>
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public final class SystemdNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

  // systemd drops a notification longer than its buffer, of PIPE_BUF (4096) bytes, as a whole, so that it would keep showing the previous status: the
  // status, which follows STATUS= in its notification, is cut to fit in UTF-8, which the notifications are sent in
  static final int STATUS_MAX_BYTES = 4096 - "STATUS=".length();

  private static final String STATUS_CUT = "…";

  private SystemdNotify() {
  }

  /**
   * Allows knowing if this library is usable under current execution conditions (operating system type, systemd available, etc...).
   *
   * @return {@code true} if and only if the library can be used
   */
  static boolean usable() {
    return NotifyChannel.initialized();
  }

  /**
   * Allows knowing why this library is not usable when systemd expects notifications (the environment variable {@code NOTIFY_SOCKET} is set).
   *
   * @return the error raised while setting up the notifications, such as when the native libsystemd library cannot be loaded on Java 17 to 21, or empty if
   *     they were set up or there was no need to
   */
  static Optional<Throwable> loadFailure() {
    return Optional.ofNullable(NotifyChannel.failure());
  }

  /**
   * Allows knowing how the notifications are sent.
   *
   * @return the Foreign Function and Memory API, on Java 22 and later, libsystemd through JNA, or none when they could not be set up, or there was no need
   */
  static String channel() {
    return NotifyChannel.implementation();
  }

  /**
   * Notifies systemd that the program has completed startup.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#READY=1">ready</a>
   */
  static void ready() {
    if (usable()) {
      logger.info("Notifying systemd that service is ready");
      invoke("READY=1");
    }
  }

  /**
   * Notifies systemd about the current status of the program.
   *
   * <p>systemd reads each line of a notification as a separate assignment, so a line break in the status would let it send other notifications, such as
   * {@code READY=1} or {@code WATCHDOG=1}: line breaks are replaced by spaces, so that the status is always sent as a single {@code STATUS=} assignment. A
   * status longer than systemd accepts, of 4089 bytes in UTF-8, is cut to fit (see {@link #cut(String, int)}).
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#STATUS=%E2%80%A6">status</a>
   */
  static void status(String message) {
    requireNonNull(message, "Message must not be null");
    if (usable()) {
      String status = cut(LINE_BREAKS.matcher(message).replaceAll(" "), STATUS_MAX_BYTES);
      logger.debug("Notifying systemd that service status is {}", status);
      invoke("STATUS=" + status);
    }
  }

  /**
   * Cuts a status, so that it fits in the given number of bytes in UTF-8: a status that does not fit is replaced by the beginning of it that fits, followed
   * by an ellipsis. It is only ever cut between two characters, as splitting one, between the bytes of its UTF-8 encoding, would make it invalid UTF-8,
   * which systemd ignores, clearing the status.
   *
   * @param status the status
   * @param maxBytes the number of bytes the status must fit in, enough for at least the ellipsis
   * @return the status, if it fits, or the beginning of it that fits, followed by an ellipsis
   */
  static String cut(String status, int maxBytes) {
    if (utf8Length(status) <= maxBytes) {
      return status;
    }
    return beginning(status, maxBytes - utf8Length(STATUS_CUT)) + STATUS_CUT;
  }

  // the longest beginning of the text whose UTF-8 encoding fits in the given number of bytes: an encoder that runs out of room stops before the character
  // that does not fit, never between the bytes of a character, nor between the two halves of a surrogate pair; an invalid character is encoded as a
  // question mark, as when the status is sent
  private static String beginning(String text, int maxBytes) {
    CharBuffer in = CharBuffer.wrap(text);
    UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        .encode(in, ByteBuffer.allocate(Math.max(maxBytes, 0)), true);
    return text.substring(0, in.position());
  }

  private static int utf8Length(String text) {
    return text.getBytes(UTF_8).length;
  }

  /**
   * Notifies systemd to extend the startup or shutdown timeout for the specified microseconds. Available in systemd versions 236 and above.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#EXTEND_TIMEOUT_USEC=%E2%80%A6">extend timeout</a>
   * @see <a href="https://github.com/systemd/systemd/blob/master/NEWS">news</a>
   */
  static void extendTimeout(long timeout) {
    if (usable()) {
      logger.debug("Extending startup timeout with {} microseconds", timeout);
      invoke("EXTEND_TIMEOUT_USEC=" + timeout);
    }
  }

  /**
   * Notifies systemd that the program is still alive and well.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#WATCHDOG=1">watchdog</a>
   */
  static void watchdog() {
    if (usable()) {
      logger.debug("Updating watchdog timestamp");
      invoke("WATCHDOG=1");
    }
  }

  /**
   * Notifies systemd that the program detected an error it cannot recover from, which systemd handles as it does a watchdog timeout, right away. Available
   * in systemd versions 243 and above, and ignored by the older ones.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#WATCHDOG=trigger">watchdog trigger</a>
   */
  static void watchdogTrigger() {
    if (usable()) {
      logger.warn("Notifying systemd to trigger the watchdog");
      invoke("WATCHDOG=trigger");
    }
  }

  /**
   * Notifies systemd that the program is stopping.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#STOPPING=1">stopping</a>
   */
  static void stopping() {
    if (usable()) {
      logger.info("Notifying systemd that service is stopping");
      invoke("STOPPING=1");
    }
  }

  /**
   * Low level method that sends the {@code sd_notify} formatted message to systemd.
   *
   * @param message the message to send, according to <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#Description">specification</a>
   */
  private static void invoke(String message) {
    if (usable() && message != null && !message.isEmpty()) {
      NotifyChannel.send(message);
    }
  }

  /**
   * Registers a JVM shutdown hook that closes the systemd integration channel.
   *
   * @see Runtime#addShutdownHook(Thread)
   * @see #close()
   */
  public static void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(SystemdNotify::close));
  }

  /**
   * Attempts to orderly close the systemd integration channel.
   *
   * <p>Normally, the integration channel will be closed when the JVM shuts down.<br>
   * However, you may wish of explicitly close the integration channel, to ensure that all closeable resources are effectively closed.<br> As such, this method
   * should only be called at most once during the lifecycle of the JVM.
   *
   * <p>Currently this does nothing, as no integration channel keeps resources open between notifications: the native libsystemd library opens a socket for
   * each notification, and so does the channel used on Java 22 and later.
   */
  public static void close() {
  }
}
