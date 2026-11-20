/*
 * Copyright 2018-2026 Joao Silva
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
 * Interface that represents any object that can contribute with status information regarding the running process to the service supervisor.
 *
 * <p>Status providers can be registered through {@link com.github.jpmsilva.jsystemd.Systemd#addStatusProviders(StatusProvider...)}
 *
 * <p>A status provider is asked for its status in every state of the application, unless it implements some of {@link StartingStatusProvider},
 * {@link RunningStatusProvider} and {@link StoppingStatusProvider}, in which case it is only asked for it in those states.
 *
 * <p>Status providers only report the status: they must not drive the lifecycle of the application with {@link Systemd#ready()},
 * {@link Systemd#stopping()} or {@link Systemd#close()}, as they are called from the periodic status updates, which {@link Systemd#close()} waits for.
 *
 * @author Joao Silva
 * @see Systemd#addStatusProviders(StatusProvider...)
 */
public interface StatusProvider {

  /**
   * Exposes status information to send to systemd.
   *
   * @return the string message to add to the service status
   */
  default String status() {
    return "";
  }
}
