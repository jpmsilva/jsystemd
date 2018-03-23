/*
 * Copyright 2018 Joao Silva
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

import org.slf4j.Logger;

/**
 * Generic utilities for the systemd library. Not meant to be used directly or instantiated.
 *
 * @author Joao Silva
 */
abstract class SystemdUtilities {

  private SystemdUtilities() {
  }

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final String notifySocket = System.getenv("NOTIFY_SOCKET");
  private static final String[] implClasses = new String[]{
      "com.github.jpmsilva.jsystemd.SystemdNotifyNative",
      "com.github.jpmsilva.jsystemd.SystemdNotifyProcess"
  };
  private static final SystemdNotify SYSTEMD_NOTIFY = initSystemdNotify();

  /**
   * Logs information regarding the status of the integration with systemd. Meant to be used once the application has done sufficient work to initialize
   * logging.
   *
   * <p>Only logs information when running under Linux.
   */
  static void logSystemdStatus() {
    if (isLinux()) {
      logger.info("Chosen systemd notify library: \"" + SYSTEMD_NOTIFY
          + "\", OS name: \"" + osName() + "\""
          + ", notify socket: \"" + notifySocket() + "\"");
    }
  }

  /**
   * Allows determining if the process is running under systemd.
   *
   * <p>A process is said to be running under systemd if <ol> <li>the operating system name contains {@code linux}.</li> <li>an environment property {@code
   * NOTIFY_SOCKET} exists</li> <li>an implementation of {@link SystemdNotify} was able to initialize itself</li> </ol>
   *
   * @return {@code true} if the process is running under systemd
   * @see SystemdNotify#usable()
   */
  static boolean isUnderSystemd() {
    return isLinux() && hasNotifySocket();
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
   * @return the contents of the environment property {@code NOTIFY_SOCKET}
   */
  static String notifySocket() {
    return notifySocket;
  }

  /**
   * Allows determining the current {@link SystemdNotify} implementation.
   *
   * @return the current {@link SystemdNotify} implementation
   */
  static SystemdNotify getSystemdNotify() {
    return SYSTEMD_NOTIFY;
  }

  private static SystemdNotify initSystemdNotify() {
    SystemdNotify systemdNotify = new SystemdNotifyNoop();
    if (isUnderSystemd()) {
      for (String implClass : implClasses) {
        SystemdNotify impl = getImpl(implClass);
        if (impl != null) {
          systemdNotify = impl;
          break;
        }
      }
    }
    return systemdNotify;
  }

  @SuppressWarnings("checkstyle:EmptyCatchBlock")
  private static SystemdNotify getImpl(String implClass) {
    try {
      SystemdNotify systemdNotify = (SystemdNotify) Class.forName(implClass).newInstance();
      if (systemdNotify.usable()) {
        return systemdNotify;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static boolean isLinux() {
    return osName().toLowerCase().startsWith("linux");
  }

  private static boolean hasNotifySocket() {
    return null != notifySocket;
  }
}
