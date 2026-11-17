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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import java.util.Map;

/**
 * The {@code sd_notify} function of the native libsystemd library, called through JNA: how the notifications are sent on Java 17 to 21, and on Java 22 and
 * later, to the addresses that are not Unix domain sockets, such as {@code vsock:} addresses.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify</a>
 */
final class Libsystemd {

  private Libsystemd() {
  }

  /**
   * Loads the native library, which must be done once before calling {@link #sd_notify(int, String)}.
   *
   * @throws UnsatisfiedLinkError if libsystemd cannot be loaded
   * @throws NoClassDefFoundError if JNA is not on the class path
   */
  static void load() {
    // the strings are converted to UTF-8, as systemd reads the notifications in UTF-8, ignoring a status that is not, rather than to the encoding of the
    // platform, as JNA does by default, which is that of the locale, such as ASCII in the C locale, where any other character becomes a question mark
    Native.register(Libsystemd.class, NativeLibrary.getInstance("systemd",
        Map.of(Library.OPTION_CLASSLOADER, Libsystemd.class.getClassLoader(), Library.OPTION_STRING_ENCODING, UTF_8.name())));
  }

  @SuppressWarnings({"UnusedReturnValue", "checkstyle:ParameterName", "checkstyle:MethodName"})
  static native int sd_notify(int unset_environment, String state);
}
