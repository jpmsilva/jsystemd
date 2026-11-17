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

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.mockito.MockedStatic.Verification;

/**
 * Gives the tests of other packages a process of their own, with a {@link Systemd} instance of its own, and the notifications that the instance sends to
 * systemd, to stub or verify with a static mock of {@link SystemdNotify}.
 *
 * @author Joao Silva
 */
public final class SystemdProcess {

  private SystemdProcess() {
  }

  /**
   * Forgets the instance of this process, as each test acts as if it were a process of its own.
   */
  public static void reset() {
    Systemd.resetProcess();
  }

  /**
   * Replaces the instance of this process, such as with a mock.
   *
   * @param systemd the instance to use from now on
   */
  public static void replace(@NonNull Systemd systemd) {
    Systemd.replaceInstance(systemd);
  }

  /**
   * Sends a status, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @param status the status, or an argument matcher of it
   * @return the notification
   */
  public static @NonNull Verification status(@NonNull Supplier<String> status) {
    return () -> SystemdNotify.status(status.get());
  }

  /**
   * Extends the timeout, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @param amount the amount, or an argument matcher of it
   * @return the notification
   */
  public static @NonNull Verification extendTimeout(@NonNull LongSupplier amount) {
    return () -> SystemdNotify.extendTimeout(amount.getAsLong());
  }
}
