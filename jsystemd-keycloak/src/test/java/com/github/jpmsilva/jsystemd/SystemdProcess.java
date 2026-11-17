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

/**
 * Gives the tests of other packages a process of their own, with a {@link Systemd} instance of its own.
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
}
