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

class SystemdUtilities {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final String notifySocket = System.getenv("NOTIFY_SOCKET");
  private static final String[] implClasses = new String[]{
      "com.github.jpmsilva.jsystemd.SystemdNotifyNative",
      "com.github.jpmsilva.jsystemd.SystemdNotifyProcess"
  };
  private static final SystemdNotify SYSTEMD_NOTIFY = initSystemdNotify();

  static void logSystemdStatus() {
    if (isLinux()) {
      logger.info("Chosen systemd notify library: \"" + SYSTEMD_NOTIFY
          + "\", OS name: \"" + osName() + "\""
          + ", notify socket: \"" + notifySocket() + "\"");
    }
  }

  static boolean isUnderSystemd() {
    return isLinux() && hasNotifySocket();
  }

  static String osName() {
    return System.getProperty("os.name");
  }

  static String notifySocket() {
    return notifySocket;
  }

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
