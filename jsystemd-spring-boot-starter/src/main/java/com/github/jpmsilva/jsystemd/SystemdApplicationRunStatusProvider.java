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

import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Implementation of {@link SystemdStatusProvider} that provides information regarding the Spring Boot application startup sequence state.
 *
 * @author Joao Silva
 * @see SystemdSpringApplicationRunListener
 * @see org.springframework.boot.SpringApplicationRunListener
 */
@Order(-5000)
public class SystemdApplicationRunStatusProvider implements SystemdStatusProvider {

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
  SystemdApplicationRunStatusProvider(@SuppressWarnings("SameParameterValue") @NotNull Systemd systemd, int applicationId) {
    this.systemd = systemd;
    this.applicationId = applicationId;
    this.systemd.addStatusProviders(0, this);
  }

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
   * Updates the current application startup sequence state of the application, sending the update to systemd as well.
   *
   * @param state the current application startup sequence state
   * @param timeTaken the time taken for the application to reach this state
   */
  public void state(@NotNull ApplicationState state, @NotNull Duration timeTaken) {
    status = String.format("Application %d state: %s, time taken: %s", applicationId, state.toString().toLowerCase().replace("_", " "),
        formatDuration(timeTaken));
    systemd.extendTimeout();
    systemd.updateStatus();
  }

  private String formatDuration(Duration duration) {
    long hours = duration.toHours();
    int minutes = duration.toMinutesPart();
    int seconds = duration.toSecondsPart();
    if (hours > 0) {
      return String.format("%dh%02dm%02ds", hours, minutes, seconds);
    } else if (minutes > 0) {
      return String.format("%dm%02ds", minutes, seconds);
    } else {
      return String.format("%ds", seconds);
    }
  }

  /**
   * Enumeration of supported application startup sequence state.
   */
  public enum ApplicationState {

    /**
     * The application is starting.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#starting(ConfigurableBootstrapContext)
     */
    STARTING,

    /**
     * The application environment is prepared.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#environmentPrepared(ConfigurableBootstrapContext, ConfigurableEnvironment)
     */
    ENVIRONMENT_PREPARED,

    /**
     * The application context is prepared.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#contextPrepared(ConfigurableApplicationContext)
     */
    CONTEXT_PREPARED,

    /**
     * The application context is loaded.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#contextLoaded(ConfigurableApplicationContext)
     */
    CONTEXT_LOADED,

    /**
     * The application is started.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#started(ConfigurableApplicationContext, Duration)
     */
    STARTED,

    /**
     * The application is ready.
     *
     * @see org.springframework.boot.SpringApplicationRunListener#ready(ConfigurableApplicationContext, Duration)
     */
    READY
  }
}
