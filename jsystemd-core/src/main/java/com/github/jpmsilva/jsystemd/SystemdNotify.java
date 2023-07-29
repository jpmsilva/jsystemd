/*
 * Copyright 2018-2023 Joao Silva
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
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

import com.sun.jna.Native;
import java.net.UnixDomainSocketAddress;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Low level API that interfaces with systemd through a {@link UnixDomainSocketAddress}. Not meant for direct usage.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify</a>
 */
public class SystemdNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  /**
   * Allows knowing if this library is usable under current execution conditions (operating system type, systemd available, etc...).
   *
   * @return {@code true} if and only if the library can be used
   */
  static boolean usable() {
    return Library.initialized;
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
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#STATUS=%E2%80%A6">status</a>
   */
  static void status(@NotNull String message) {
    if (usable()) {
      logger.debug("Notifying systemd that service status is {}", requireNonNull(message, "Message must not be null"));
      invoke("STATUS=" + message);
    }
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
      Library.sd_notify(0, message);
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
   * <p>Currenty this does nothing, as currently the only supported integration channel is the native libsystemd library, which does not need any cleanup.
   */
  public static void close() {
  }

  private static class Library {

    private static boolean initialized = false;

    static {
      try {
        Native.register("systemd");
        initialized = true;
      } catch (UnsatisfiedLinkError ignored) {
      }
    }

    @SuppressWarnings({"UnusedReturnValue", "checkstyle:ParameterName"})
    public static native int sd_notify(int unset_environment, String state);
  }
}
