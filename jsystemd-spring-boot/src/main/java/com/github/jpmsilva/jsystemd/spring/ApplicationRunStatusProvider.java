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

package com.github.jpmsilva.jsystemd.spring;

import com.github.jpmsilva.jsystemd.StartingStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the Spring Boot application startup sequence state. Its state is
 * meant to be updated by a {@link org.springframework.boot.SpringApplicationRunListener}, as the one of the Spring Boot starter does. It only reports while
 * the application starts up, as a {@link StartingStatusProvider}.
 *
 * @author Joao Silva
 * @see org.springframework.boot.SpringApplicationRunListener
 */
public class ApplicationRunStatusProvider implements StartingStatusProvider {

  // written during startup, read by the periodic status update task
  private volatile String status = "";

  /**
   * Creates a new instance, which sends the status to systemd on every change of state.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   */
  public ApplicationRunStatusProvider() {
  }

  @Override
  public String status() {
    return status;
  }

  /**
   * Updates the current application startup sequence state of the application, sending the update to systemd as well.
   *
   * @param state the current application startup sequence state
   */
  public void state(ApplicationState state) {
    status = String.format(Locale.ROOT, "State: %s", describe(state));
    Systemd systemd = Systemd.instance();
    systemd.extendTimeout();
    systemd.updateStatus();
  }

  /**
   * Updates the current application startup sequence state of the application, sending the update to systemd as well.
   *
   * @param state the current application startup sequence state
   * @param timeTaken the time taken for the application to reach this state, if known
   */
  public void state(ApplicationState state, @Nullable Duration timeTaken) {
    status = timeTaken == null ? String.format(Locale.ROOT, "State: %s", describe(state))
        : String.format(Locale.ROOT, "State: %s, time taken: %s", describe(state), formatDuration(timeTaken));
    Systemd systemd = Systemd.instance();
    systemd.extendTimeout();
    systemd.updateStatus();
  }

  // the root locale keeps the text the same everywhere: under a Turkish locale, for instance, the "I" in "STARTING" would become a dotless "ı"
  private static String describe(ApplicationState state) {
    return state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private String formatDuration(Duration duration) {
    long hours = duration.toHours();
    int minutes = duration.toMinutesPart();
    int seconds = duration.toSecondsPart();
    if (hours > 0) {
      return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds);
    } else if (minutes > 0) {
      return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds);
    } else {
      return String.format(Locale.ROOT, "%ds", seconds);
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
