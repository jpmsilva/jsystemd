/*
 * Copyright 2020 TomTom N.V.
 * Copyright 2023-2026 Joao Silva
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

import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Provides the application health based on Spring Boot Actuator health contributors: as a {@link HealthProvider}, for the systemd watchdog, and as a
 * {@link StatusProvider}, for the systemd status.
 *
 * <p>The health contributors are those of the Spring Boot health contributor registries, the ones shown by the health endpoint, with the same names, such
 * as {@code db} or {@code diskSpace}. They are read on every evaluation, so that contributors registered or unregistered later are taken into account. A
 * composite contributor, such as the one of several data sources, is unhealthy while any of its members is, and its details are those of its unhealthy
 * members, under their names.
 *
 * <p>The health indicators can be limited to the members of a health group of Spring Boot Actuator, such as one set up with
 * {@code management.endpoint.health.group.<name>.include}: the others are neither shown in the status nor taken into account for the health. Membership is
 * tested as the health endpoint does, with the path of each health indicator, such as {@code db}, or {@code db/primary} for a member of a composite.
 *
 * <p>The application is unhealthy while any of the health contributors reports one of the status codes considered as unhealthy. The systemd status always
 * shows that health, while the watchdog is only told about it when enabled, as it lets systemd restart the application: otherwise, the application is
 * always healthy for the watchdog. As the health contributors often depend on external systems, whose outages may be short, reporting the unhealthy state
 * to the watchdog can also be delayed, with {@link #setUnhealthyDelay(Duration)}. The systemd status always shows the actual health, without the
 * delay.
 *
 * @author Christian Lorenz
 * @author Joao Silva
 */
public class ActuatorHealthStatusProvider extends DelayableHealthProvider implements StatusProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private final HealthContributors contributors;
  private final Set<Status> unhealthyStatusCodes;
  private final Predicate<String> member;

  private final boolean health;

  /**
   * Creates a new instance using the provided health contributors and unhealthy status codes.
   *
   * @param contributors the health contributors, such as the Spring Boot health contributor registry: the details of each unhealthy contributor are
   *     reported under its name
   * @param unhealthyStatusCodes the status codes considered as unhealthy
   * @param member tells if a health indicator, by its path, such as {@code db} or {@code db/primary}, is shown in the status and taken into account for
   *     the health, such as {@link org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup#isMember(String)}, or <code>name -&gt; true</code>
   *     for all of them
   * @param health <code>true</code> to report the unhealthy state to the watchdog, <code>false</code> to only show it in the systemd status
   */
  public ActuatorHealthStatusProvider(HealthContributors contributors, Set<Status> unhealthyStatusCodes, Predicate<String> member,
      boolean health) {
    this.contributors = requireNonNull(contributors, "Contributors must not be null");
    this.unhealthyStatusCodes = Set.copyOf(requireNonNull(unhealthyStatusCodes, "Unhealthy status codes must not be null"));
    this.member = requireNonNull(member, "Member must not be null");
    this.health = health;
    logger.debug("Status codes considered as unhealthy={}", unhealthyStatusCodes);
  }

  /**
   * Provides the health of the application for the watchdog, without the delay: always healthy unless reporting the unhealthy state to the watchdog is
   * enabled.
   *
   * @return the health of the application for the watchdog
   */
  @Override
  protected Health evaluateHealth() {
    return health ? contributorsHealth() : Health.HEALTHY;
  }

  private Health contributorsHealth() {
    Map<String, Object> details = new LinkedHashMap<>();
    // each contributor is evaluated only once, as evaluating it may be expensive and its result may change between evaluations
    contributors.stream()
        .forEach(entry -> {
          Object unhealthy = unhealthyDetails(entry.name(), entry.contributor());
          if (unhealthy != null) {
            details.put(entry.name(), unhealthy);
          }
        });
    logger.debug("Application unhealthy contributors={}", details);
    return new Health(details.isEmpty(), details);
  }

  /**
   * Returns the details of the contributor if unhealthy, or <code>null</code> if healthy or left out: those of the indicator, or those of the unhealthy
   * members of the composite, under their names. An indicator that throws is considered down, with the exception in its details, so that it neither fails
   * the status update nor hides the other contributors.
   */
  private @Nullable Object unhealthyDetails(String path, HealthContributor contributor) {
    if (contributor instanceof HealthIndicator indicator) {
      // left out without being evaluated, as evaluating it may be expensive
      if (!member.test(path)) {
        return null;
      }
      org.springframework.boot.health.contributor.Health health;
      try {
        health = indicator.health();
      } catch (Exception e) {
        // as AbstractHealthIndicator reports an exception, for indicators that implement HealthIndicator directly
        logger.debug("Health indicator {} failed", path, e);
        health = org.springframework.boot.health.contributor.Health.down(e).build();
      }
      return health != null && unhealthyStatusCodes.contains(health.getStatus()) ? health.getDetails() : null;
    }
    if (contributor instanceof CompositeHealthContributor composite) {
      Map<String, Object> details = new LinkedHashMap<>();
      composite.stream().forEach(entry -> {
        Object unhealthy = unhealthyDetails(path + "/" + entry.name(), entry.contributor());
        if (unhealthy != null) {
          details.put(entry.name(), unhealthy);
        }
      });
      return details.isEmpty() ? null : details;
    }
    return null;
  }

  /**
   * Provides the application health for the systemd status, naming the unhealthy contributors, if any.
   *
   * <p>The details of the unhealthy contributors are left out, as they often contain sensitive information (error messages, host names, paths, ...), and
   * the systemd status can be read by any local user, for instance with {@code systemctl status}, regardless of how health details are exposed by Spring
   * Boot Actuator. The details are logged instead when the watchdog heartbeat is suppressed.
   *
   * @return the application health, such as {@code health status: unhealthy (db, redis)}
   */
  @Override
  public String status() {
    Health health = contributorsHealth();
    return health.healthy() ? "health status: healthy" : "health status: unhealthy (" + String.join(", ", health.details().keySet()) + ")";
  }
}
