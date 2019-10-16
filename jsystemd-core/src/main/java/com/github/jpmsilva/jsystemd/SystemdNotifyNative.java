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

import com.sun.jna.Native;

/**
 * Implementation of {@link AbstractSystemdNotify} that interfaces with systemd through a native library.
 *
 * @author Joao Silva
 * @see Native#register(String)
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify</a>
 */
@SuppressWarnings("unused")
final class SystemdNotifyNative extends AbstractSystemdNotify {

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean usable() {
    return Library.initialized;
  }

  /**
   * {@inheritDoc}
   */
  protected void invoke(String message) {
    Library.sd_notify(0, message);
  }

  @SuppressWarnings({"checkstyle:EmptyCatchBlock", "checkstyle:ParameterName"})
  private static class Library {

    private static boolean initialized = false;

    static {
      try {
        Native.register("systemd");
        initialized = true;
      } catch (UnsatisfiedLinkError ignored) {
      }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static native int sd_notify(int unset_environment, String state);
  }
}
