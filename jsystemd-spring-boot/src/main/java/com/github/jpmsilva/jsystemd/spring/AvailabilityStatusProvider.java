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

package com.github.jpmsilva.jsystemd.spring;

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;

/**
 * Provides the availability of the application, as Spring Boot reports it with its {@link AvailabilityChangeEvent}: as a {@link StatusProvider},
 * whether it is live, and whether it is ready to accept traffic, such as {@code Liveness: correct; readiness: accepting traffic}, and as a
 * {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy while its liveness is broken, if enabled.
 *
 * <p>Spring Boot reports the application as live and ready once it starts, and, for web applications, as refusing traffic once it starts shutting down.
 * The application can also report a change of availability itself, such as {@link LivenessState#BROKEN} when it can no longer recover, with
 * {@link AvailabilityChangeEvent#publish(org.springframework.context.ApplicationEventPublisher, Object, AvailabilityState)}.
 *
 * <p>Each state is only reported once it is first published.
 *
 * <p>The health is enabled on its own, as it lets systemd restart the application: while disabled, the application is always healthy. When enabled, it is
 * unhealthy while the liveness state is {@link LivenessState#BROKEN}, which Spring Boot defines as a state the application cannot recover from, so that
 * restarting it is the way out: it is reported as unrecoverable, which triggers the watchdog right away, rather than once {@code WatchdogSec} elapses.
 * The state is reported in the health details, under {@code livenessState}. Unlike the health indicators of Spring Boot Actuator, the liveness state does
 * not depend on external systems. The unhealthy state can still be delayed, with {@link #setUnhealthyDelay(java.time.Duration)}, for applications that
 * may report a broken liveness state and then recover from it: the watchdog is then only triggered once the delay elapses.
 *
 * @author Joao Silva
 */
public class AvailabilityStatusProvider extends DelayableHealthProvider implements StatusProvider, ApplicationListener<AvailabilityChangeEvent<?>> {

  private final boolean health;

  // written by the event listener, read by the status updates
  @Nullable
  private volatile LivenessState liveness;

  @Nullable
  private volatile ReadinessState readiness;

  /**
   * Create a new AvailabilityStatusProvider.
   *
   * @param health <code>true</code> to report the application as unhealthy while its liveness state is broken
   */
  public AvailabilityStatusProvider(boolean health) {
    this.health = health;
  }

  // for tests: whether a broken liveness state reports the application as unhealthy
  boolean healthEnabled() {
    return health;
  }

  /**
   * Keeps the latest liveness and readiness states of the event. Other states are ignored.
   *
   * @param event the event of a change of availability
   */
  @Override
  public void onApplicationEvent(AvailabilityChangeEvent<?> event) {
    AvailabilityState state = event.getState();
    if (state instanceof LivenessState livenessState) {
      liveness = livenessState;
    } else if (state instanceof ReadinessState readinessState) {
      readiness = readinessState;
    }
  }

  @Override
  public String status() {
    List<String> details = new ArrayList<>();
    LivenessState liveness = this.liveness;
    if (liveness != null) {
      details.add("Liveness: " + describe(liveness));
    }
    ReadinessState readiness = this.readiness;
    if (readiness != null) {
      details.add((details.isEmpty() ? "Readiness: " : "readiness: ") + describe(readiness));
    }
    return String.join("; ", details);
  }

  /**
   * Provides the health of the application, without the delay: unhealthy, and unrecoverable, while the liveness state is broken, if enabled.
   *
   * @return the health of the application
   */
  @Override
  protected Health evaluateHealth() {
    return health && liveness == LivenessState.BROKEN ? Health.unrecoverable(Map.of("livenessState", LivenessState.BROKEN.name())) : Health.HEALTHY;
  }

  // the root locale keeps the text the same everywhere: under a Turkish locale, for instance, the "I" in "ACCEPTING" would become a dotless "ı"
  private static String describe(Enum<?> state) {
    return state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
