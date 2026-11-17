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
 * A health provider can be used to control the watchdog heartbeat. An unhealthy state does not trigger the heartbeat.
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
   * @param healthy <code>true</code> if this object represents a healthy state, <code>false</code> otherwise
   * @param details additional health details for each application component
   */
  record Health(boolean healthy, Map<String, Object> details) {

    /**
     * A health object that represents a healthy application state.
     */
    public static final Health HEALTHY = new Health(true, Collections.emptyMap());

    /**
     * Provides for a health representation (healthy/unhealthy), with health details for each component.
     *
     * @param healthy <code>true</code> if this object represents a healthy state, <code>false</code> otherwise
     * @param details additional health details for each application component
     * @throws NullPointerException if details is null
     */
    public Health {
      details = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(details, "Details must not be null")));
    }
  }
}
