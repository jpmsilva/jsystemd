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
  boolean healthy();
}
