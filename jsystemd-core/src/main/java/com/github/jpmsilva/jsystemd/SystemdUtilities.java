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
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * Generic utilities for the systemd library. Not meant to be used directly or instantiated.
 *
 * @author Joao Silva
 */
abstract class SystemdUtilities {

  private static final Logger logger = getLogger(lookup().lookupClass());
  private static final String notifySocket = System.getenv("NOTIFY_SOCKET");

  private static final Path notifySocketPath;

  static {
    if (notifySocket != null) {
      notifySocketPath = Path.of(notifySocket);
    } else {
      notifySocketPath = null;
    }
  }

  private static final long watchdogUsec = readWatchdogUsec();

  private SystemdUtilities() {
  }

  private static long readWatchdogUsec() {
    String watchdogUsec = System.getenv("WATCHDOG_USEC");
    if (isNotEmpty(watchdogUsec)) {
      try {
        return Long.parseLong(watchdogUsec);
      } catch (NumberFormatException e) {
        logger.warn("Value of environment property WATCHDOG_USEC cannot be read as a number - watchdog disabled: " + watchdogUsec);
      }
    }
    return 0;
  }

  private static boolean isNotEmpty(String input) {
    return input != null && !input.isEmpty();
  }

  /**
   * Allows determining if the process is running under systemd.
   *
   * <p>A process is said to be running under systemd if <ol> <li>the operating system name contains {@code linux}.</li> <li>an environment property {@code
   * NOTIFY_SOCKET} exists</li> <li>{@link SystemdNotify} was able connect to the socket</li> </ol>
   *
   * @return {@code true} if the process is running under systemd
   */
  static boolean isUnderSystemd() {
    return SystemdNotify.usable();
  }

  /**
   * Allows determining the current OS name.
   *
   * @return the contents of the system property {@code os.name}
   */
  static String osName() {
    return System.getProperty("os.name");
  }

  /**
   * Allows determining the current systemd notify socket.
   *
   * @return the {@link Path} corresponding the contents of the environment variable {@code NOTIFY_SOCKET}, or <code>null</code> if the environment variable is
   *     not set
   */
  static Path notifySocketPath() {
    return notifySocketPath;
  }

  /**
   * Allows determining the current watchdog keep-alive.
   *
   * @return the contents of the environment property {@code WATCHDOG_USEC} as a long, or 0 if undefined, empty or unparseable
   */
  static long watchdogUsec() {
    return watchdogUsec;
  }

  static boolean isLinux() {
    return osName().toLowerCase().startsWith("linux");
  }

  static boolean hasNotifySocket() {
    return notifySocketPath != null && notifySocketPath.toFile().exists();
  }

  private static final String[] unitPrefixes = new String[]{"", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei"};

  static String formatByteCount(long bytes) {
    long abs = Math.abs(bytes);
    if (bytes == Long.MIN_VALUE) {
      abs = Long.MAX_VALUE;
    }

    int divider = 0;
    int index = 0;
    long remainder = abs;
    while (remainder >> divider >= 1024) {
      divider += 10;
      index += 1;
    }

    double division = abs / Long.valueOf(1L << divider).doubleValue() * Math.signum(bytes);
    return String.format("%,.1f %sB", division, unitPrefixes[index]);
  }
}
