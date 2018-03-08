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

class SystemdUtilities {

  private static final String notifySocket = System.getenv("NOTIFY_SOCKET");
  private static final String[] implClasses = new String[]{
      "com.github.jpmsilva.jsystemd.SystemdNotifyNative",
      "com.github.jpmsilva.jsystemd.SystemdNotifyProcess"
  };
  private static final SystemdNotify SYSTEMD_NOTIFY = initSystemdNotify();

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
      System.out.println(
          "Choosing systemd notify library " + systemdNotify.getClass().getName()
              + ", OS name: " + System.getProperty("os.name")
              + ", notify socket: " + notifySocket);
    }
    return systemdNotify;
  }

  static boolean isUnderSystemd() {
    return System.getProperty("os.name").toLowerCase().startsWith("linux") && null != notifySocket;
  }

  static String notifySocket() {
    return notifySocket;
  }

  static SystemdNotify getSystemdNotify() {
    return SYSTEMD_NOTIFY;
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

}
