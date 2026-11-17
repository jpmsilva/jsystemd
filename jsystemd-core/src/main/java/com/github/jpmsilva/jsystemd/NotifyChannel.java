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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.hasNotifySocket;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.isLinux;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.notifySocket;
import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The channel the notifications are sent to systemd through.
 *
 * <p>It is chosen once, when systemd expects notifications:
 * <ol>
 *   <li>on Java 22 and later, when {@code NOTIFY_SOCKET} is a Unix domain socket, as systemd sets it for its services, the {@link NotifySocket}, which
 *   sends them itself with the Foreign Function and Memory API;</li>
 *   <li>otherwise, or if that socket cannot be set up, {@code sd_notify} from the native libsystemd library, through JNA: on Java 17 to 21, for
 *   {@code vsock:} addresses, and as a fallback.</li>
 * </ol>
 *
 * @author Joao Silva
 */
@SuppressWarnings("PMD.TooManyStaticImports")
final class NotifyChannel {

  static final String SOCKET = "Foreign Function and Memory API";
  static final String LIBSYSTEMD = "libsystemd (JNA)";
  static final String NONE = "none";

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final boolean initialized;

  // null when the notifications are sent through libsystemd, or could not be set up
  @Nullable
  private static final NotifySocket socket;

  // logging may not be set up yet when the channel is set up, so the failure is kept to be reported later
  @Nullable
  private static final Throwable failure;

  // whether the last notification failed, so that only the first of consecutive failures is warned about
  private static final AtomicBoolean failing = new AtomicBoolean();

  static {
    NotifySocket created = null;
    boolean loaded = false;
    Throwable error = null;
    if (isLinux() && hasNotifySocket()) {
      Throwable socketError = null;
      String notifySocket = notifySocket();
      if (notifySocket != null && NotifySocketAddress.isUnixSocket(notifySocket)) {
        try {
          created = NotifySocket.create(notifySocket);
        } catch (RuntimeException | LinkageError e) {
          // such as an address too long, native access denied with --illegal-native-access=deny, or a JVM without a native linker
          socketError = e;
        }
      }
      if (created != null) {
        loaded = true;
      } else {
        try {
          Libsystemd.load();
          loaded = true;
        } catch (RuntimeException | LinkageError e) {
          // libsystemd is not installed, JNA is not on the class path, or native access is denied
          if (socketError != null) {
            e.addSuppressed(socketError);
          }
          error = e;
        }
      }
    }
    socket = created;
    initialized = loaded;
    failure = error;
  }

  private NotifyChannel() {
  }

  /**
   * Allows knowing if notifications can be sent.
   *
   * @return {@code true} if and only if the notifications were set up
   */
  static boolean initialized() {
    return initialized;
  }

  /**
   * Allows knowing why notifications cannot be sent when systemd expects them.
   *
   * @return the error raised while setting up the notifications, with the error of the socket, if any, as a suppressed exception, or {@code null} if they
   *     were set up or there was no need to
   */
  static @Nullable Throwable failure() {
    return failure;
  }

  /**
   * Allows knowing how the notifications are sent.
   *
   * @return {@link #SOCKET}, {@link #LIBSYSTEMD}, or {@link #NONE} when the notifications were not set up
   */
  static String implementation() {
    if (socket != null) {
      return SOCKET;
    }
    return initialized ? LIBSYSTEMD : NONE;
  }

  /**
   * Sends a message to systemd. Must only be called once {@link #initialized()}.
   *
   * <p>A failure is not thrown, as libsystemd only reports it to its caller: systemd may well not listen anymore, as when it no longer tracks the service.
   * It is logged instead, as a warning for the first of consecutive failures, as systemd may then consider the service failed, such as when its watchdog
   * expires.
   *
   * @param message the message, according to <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#Description">specification</a>
   */
  static void send(String message) {
    String error;
    if (socket != null) {
      error = socket.send(message);
    } else {
      // a negative errno on failure
      int result = Libsystemd.sd_notify(0, message);
      error = result < 0 ? "sd_notify: errno " + -result : null;
    }
    if (error == null) {
      if (failing.compareAndSet(true, false)) {
        logger.info("Notifying systemd works again");
      }
    } else if (failing.compareAndSet(false, true)) {
      logger.warn("Could not notify systemd, which may consider the service failed, such as when its watchdog expires: {}", error);
    } else {
      logger.debug("Could not notify systemd: {}", error);
    }
  }

  // for tests: whether the last notification failed
  static boolean failing() {
    return failing.get();
  }
}
