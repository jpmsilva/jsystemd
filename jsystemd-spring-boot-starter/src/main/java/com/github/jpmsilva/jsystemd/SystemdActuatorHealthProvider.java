/*
 * Copyright 2020 TomTom N.V.
 * Copyright 2023 Joao Silva
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link HealthProvider} that provides application health based on Spring Boot Actuator Health Indicators.
 *
 * @author Christian Lorenz
 */
@Order(2000)
public class SystemdActuatorHealthProvider implements SystemdStatusProvider, HealthProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  @NotNull
  private final List<HealthIndicator> healthIndicators;
  @NotNull
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
  public SystemdActuatorHealthProvider(@NotNull List<HealthIndicator> healthIndicators, @NotNull Set<Status> unhealthyStatusCodes) {
    this.healthIndicators = Objects.requireNonNull(healthIndicators, "Health indicators must not be null");
    this.unhealthyStatusCodes = Objects.requireNonNull(unhealthyStatusCodes, "Unhealthy status codes must not be null");
    if (this.unhealthyStatusCodes.isEmpty()) {
      logger.warn("No status codes considered as unhealthy");
    } else {
      logger.debug("Status codes considered as unhealthy={}", unhealthyStatusCodes);
    }
  }

  @Override
  public Health health() {
    Collection<HealthIndicator> unhealthyIndicators = healthIndicators.stream()
        .filter(it -> unhealthyStatusCodes.contains(it.health().getStatus()))
        .toList();
    logger.debug("Application health state={}", unhealthyIndicators.stream().map(HealthIndicator::health).collect(Collectors.toList()));
    boolean healthy = unhealthyIndicators.isEmpty();
    return new Health(healthy, unhealthyIndicators.stream()
        .map(it -> it.health().getDetails().entrySet())
        .flatMap(Collection::stream)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  @Override
  public @NotNull String status() {
    Health health = health();
    return "health status: " + (health.healthy ? "healthy" : "unhealthy=" + health);
  }
}
