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

import org.jetbrains.annotations.NotNull;

/**
 * Interface that represents any object that can contribute with status information regarding the running process to the service supervisor.
 *
 * <p>Status providers can be registered through {@link com.github.jpmsilva.jsystemd.Systemd#addStatusProviders(SystemdStatusProvider...)}
 *
 * @author Joao Silva
 * @see Systemd#addStatusProviders(SystemdStatusProvider...)
 */
@SuppressWarnings("WeakerAccess")
public interface SystemdStatusProvider {

  /**
   * Exposes status information to send to systemd.
   *
   * @return the string message to add to the service status
   */
  default @NotNull String status() {
    return "";
  }
}
