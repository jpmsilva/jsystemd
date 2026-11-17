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

package com.github.jpmsilva.jsystemd.keycloak;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells if Keycloak is ready from its readiness health checks, those of its {@code /health/ready} endpoint, which are only there when the health
 * endpoints are enabled ({@code health-enabled}).
 *
 * @author Joao Silva
 */
final class HealthReadiness {

  private static final Logger logger = LoggerFactory.getLogger(HealthReadiness.class);

  // whether the last run of the checks failed, so that only the first of consecutive failures is warned about, as the checks run until they are all up
  private static final AtomicBoolean failing = new AtomicBoolean();

  private HealthReadiness() {
  }

  /**
   * The readiness health checks of Keycloak.
   */
  @FunctionalInterface
  interface Checks {

    /**
     * Runs the checks.
     *
     * @return null if the checks are all up, or else the names of those that are down, none if the checks could not run
     */
    @Nullable List<String> down();
  }

  /**
   * Looks up the readiness health checks of Keycloak.
   *
   * @return the readiness health checks, or null when Keycloak has none, as when its health endpoints are disabled, or outside of Quarkus, as in tests
   */
  static @Nullable Checks lookup() {
    try {
      ArcContainer container = Arc.container();
      // the bean is removed from a Keycloak whose health endpoints are disabled, which a lookup would warn about, unlike asking if it is there
      if (container == null || container.beanManager().getBeans(SmallRyeHealthReporter.class).isEmpty()) {
        return null;
      }
      InstanceHandle<SmallRyeHealthReporter> handle = container.instance(SmallRyeHealthReporter.class);
      if (!handle.isAvailable()) {
        return null;
      }
      SmallRyeHealthReporter reporter = handle.get();
      return () -> down(reporter);
    } catch (LinkageError e) {
      // a Keycloak without SmallRye Health or ArC, which all versions so far have
      logger.debug("Not using the readiness health checks, as they cannot be looked up", e);
      return null;
    }
  }

  private static @Nullable List<String> down(SmallRyeHealthReporter reporter) {
    try {
      SmallRyeHealth readiness = reporter.getReadiness();
      if (failing.compareAndSet(true, false)) {
        logger.info("The readiness health checks of Keycloak run again");
      }
      return readiness.isDown() ? down(readiness.getPayload()) : null;
    } catch (RuntimeException e) {
      // as when the checks cannot reach what they check
      if (failing.compareAndSet(false, true)) {
        logger.warn("Considering Keycloak not ready, as its readiness health checks failed", e);
      } else {
        logger.debug("Considering Keycloak not ready, as its readiness health checks failed", e);
      }
      return List.of();
    }
  }

  // the names of the checks that are down, from the report of the checks, as in the response of the /health/ready endpoint
  static List<String> down(JsonObject report) {
    JsonArray checks = report.getJsonArray("checks");
    if (checks == null) {
      return List.of();
    }
    return checks.getValuesAs(JsonObject.class).stream()
        .filter(check -> "DOWN".equals(check.getString("status", null)))
        .map(check -> check.getString("name", "unnamed"))
        .toList();
  }
}
