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

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Implementation of {@link HealthProvider} that provides application health based on Spring Boot Actuator Health Indicators.
 *
 * @author Christian Lorenz
 */
public class SystemdNotifyActuatorHealthProvider implements SystemdNotifyStatusProvider, HealthProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private final List<HealthIndicator> healthIndicators;
  private final Set<Status> unhealthyStatusCodes;

  /**
   * Creates a new instance using the provided {@link HealthIndicator} and {@link Status}.
   *
   * <p>{@link HealthIndicator} are used to determine the health of the application, whereas {@link Status}
   * indicate which status should be considered as unhealthy.
   *
   * @param healthIndicators Spring Boot Actuator Health Indicators
   * @param unhealthyStatusCodes list of status codes considered as unhealthy
   */
  public SystemdNotifyActuatorHealthProvider(List<HealthIndicator> healthIndicators, Set<Status> unhealthyStatusCodes) {
    this.healthIndicators = healthIndicators;
    this.unhealthyStatusCodes = unhealthyStatusCodes;
    if (this.unhealthyStatusCodes.isEmpty()) {
      logger.warn("No status codes considered as unhealthy");
    } else {
      logger.debug("Status codes considered as unhealthy={}", unhealthyStatusCodes);
    }
  }

  @Override
  public boolean healthy() {
    boolean health = healthIndicators.stream().noneMatch(it -> unhealthyStatusCodes.contains(it.health().getStatus()));
    logger.debug("Application health state={}", health);
    return health;
  }

  @Override
  public String status() {
    return "health status: " + (healthy() ? "healthy" : "unhealthy");
  }
}
