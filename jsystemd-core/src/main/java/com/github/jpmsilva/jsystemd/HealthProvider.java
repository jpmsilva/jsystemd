/*
 * Copyright 2020 TomTom N.V.
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A health provider can be used to control the watchdog heartbeat. An unhealthy state does not trigger the heartbeat.
 *
 * @author Christian Lorenz
 */
public interface HealthProvider {

  /**
   * Determines if an application is healthy.
   *
   * @return true if application is healthy, otherwise false
   */
  Health health();

  /**
   * Wrapper of health state.
   */
  class Health {

    public final boolean healthy;

    public final Map<String, Object> details;

    /**
     * Provides for a health representation (healthy/unhealthy), with health details for each component.
     *
     * @param healthy <code>true</code> if this object represents an healthy state, <code>false</code> otherwise
     * @param details additional health details for each application component
     * @throws NullPointerException if details is null
     */
    public Health(boolean healthy, Map<String, Object> details) {
      this.healthy = healthy;
      this.details = new HashMap<>(Objects.requireNonNull(details, "Details must not be null"));
    }

    /**
     * Synthesize an health object that represents an healthy application state.
     *
     * @return a synthetic healthy state.
     */
    public static Health healthy() {
      return new Health(true, Collections.emptyMap());
    }

    @Override
    public String toString() {
      return "Health{healthy=" + this.healthy + ", details=" + this.details + '}';
    }
  }
}
