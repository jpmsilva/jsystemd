/*
 * Copyright 2020 TomTom N.V.
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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A health provider can be used to control the watchdog heartbeat. An unhealthy state does not trigger the heartbeat, so that systemd restarts the
 * application once <code>WatchdogSec</code> elapses. An unhealthy state that the application cannot recover from also triggers the watchdog right away.
 *
 * <p>Health providers only report the health: they must not drive the lifecycle of the application with {@link Systemd#ready()},
 * {@link Systemd#stopping()} or {@link Systemd#close()}, as they are called from the periodic watchdog heartbeats, which {@link Systemd#close()} waits for.
 *
 * @author Christian Lorenz
 */
public interface HealthProvider {

  /**
   * Determines if an application is healthy.
   *
   * @return representation of the application {@link Health} state
   */
  Health health();

  /**
   * The health state of an application. Immutable: the details are copied, keeping their order, and cannot be modified.
   *
   * <p>An unhealthy state suppresses the watchdog heartbeat for as long as it lasts, so that systemd restarts the application once
   * <code>WatchdogSec</code> elapses, unless the application is healthy again by then. An unrecoverable one, which never resolves by itself, such as a
   * deadlock, also triggers the watchdog right away, with {@link Systemd#triggerWatchdog()}, as waiting would only delay the recovery.
   *
   * @param healthy <code>true</code> if this object represents a healthy state, <code>false</code> otherwise
   * @param details additional health details for each application component
   * @param unrecoverable <code>true</code> if this object represents an unhealthy state that the application cannot recover from
   */
  record Health(boolean healthy, Map<String, Object> details, boolean unrecoverable) {

    /**
     * A health object that represents a healthy application state.
     */
    public static final Health HEALTHY = new Health(true, Collections.emptyMap());

    /**
     * Provides for a health representation (healthy/unhealthy/unrecoverable), with health details for each component.
     *
     * @param healthy <code>true</code> if this object represents a healthy state, <code>false</code> otherwise
     * @param details additional health details for each application component
     * @param unrecoverable <code>true</code> if this object represents an unhealthy state that the application cannot recover from
     * @throws NullPointerException if details is null
     * @throws IllegalArgumentException if a healthy state is unrecoverable
     */
    public Health {
      if (healthy && unrecoverable) {
        throw new IllegalArgumentException("A healthy state cannot be unrecoverable");
      }
      details = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(details, "Details must not be null")));
    }

    /**
     * Provides for a health representation (healthy/unhealthy), with health details for each component. An unhealthy state is one the application may
     * recover from: see {@link #unrecoverable(Map)} for one it cannot.
     *
     * @param healthy <code>true</code> if this object represents a healthy state, <code>false</code> otherwise
     * @param details additional health details for each application component
     * @throws NullPointerException if details is null
     */
    public Health(boolean healthy, Map<String, Object> details) {
      this(healthy, details, false);
    }

    /**
     * Provides for an unhealthy state that the application cannot recover from, with health details for each component.
     *
     * @param details additional health details for each application component
     * @return an unhealthy state that the application cannot recover from
     * @throws NullPointerException if details is null
     */
    public static Health unrecoverable(Map<String, Object> details) {
      return new Health(false, details, true);
    }
  }
}
