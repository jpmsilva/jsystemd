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

/**
 * Interface of all systemd integration library implementations.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify specification</a>
 */
interface SystemdNotify {

  /**
   * If this library is usable under current process conditions (operating system type, systemd available, etc...).
   *
   * @return {@code true} if and only if the library can be used
   */
  default boolean usable() {
    return false;
  }

  /**
   * Notifies systemd that the service unit has completed startup.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#READY=1">ready</a>
   */
  default void ready() {
  }

  /**
   * Notifies systemd about the current status of the program.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#STATUS=%E2%80%A6">status</a>
   */
  default void status(String message) {
  }

  /**
   * Notifies systemd to extend the startup or shutdown timeout for the specified microseconds. Available in systemd versions 236 and above.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#EXTEND_TIMEOUT_USEC=%E2%80%A6">extend timeout</a>
   * @see <a href="https://github.com/systemd/systemd/blob/master/NEWS">news</a>
   */
  default void extendTimeout(long timeout) {
  }

  /**
   * Notifies systemd that the program is still alive and well.
   *
   * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#WATCHDOG=1">watchdog</a>
   */
  default void watchdog() {
  }
}
