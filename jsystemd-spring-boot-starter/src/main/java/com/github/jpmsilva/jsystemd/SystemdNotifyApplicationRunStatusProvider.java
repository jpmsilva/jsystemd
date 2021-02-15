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

import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdNotifyStatusProvider} that provides information regarding the Spring Boot application startup sequence state.
 *
 * @author Joao Silva
 * @see SystemdSpringApplicationRunListener
 * @see org.springframework.boot.SpringApplicationRunListener
 */
@Order(-5000)
public class SystemdNotifyApplicationRunStatusProvider implements SystemdNotifyStatusProvider {

  @NotNull
  private final Systemd systemd;
  private final int applicationId;
  @NotNull
  private String status = "";

  /**
   * Creates a new instance using the provided {@link Systemd} as the integration point.
   *
   * <p>This constructor automatically registers the created instance as a status provider in the {@link Systemd} passed as a parameter.
   *
   * @param systemd the {@link Systemd} to send status information to
   */
  SystemdNotifyApplicationRunStatusProvider(@SuppressWarnings("SameParameterValue") @NotNull Systemd systemd, int applicationId) {
    this.systemd = systemd;
    this.applicationId = applicationId;
    this.systemd.addStatusProviders(0, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @NotNull String status() {
    return systemd.isReady() ? "" : status;
  }

  /**
   * Updates the current application startup sequence state of the application, sending the update to systemd as well.
   *
   * @param state the current application startup sequence state
   */
  void state(@NotNull ApplicationState state) {
    status = String.format("Application %d state: %s", applicationId, state.toString().toLowerCase().replace("_", " "));
    systemd.extendTimeout();
    systemd.updateStatus();
  }

  /**
   * Enumeration of supported application startup sequence state.
   */
  public enum ApplicationState {
    STARTING, ENVIRONMENT_PREPARED, CONTEXT_PREPARED, CONTEXT_LOADED
  }
}
