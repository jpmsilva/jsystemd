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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HealthReadiness}.
 *
 * @author Joao Silva
 */
class HealthReadinessTest {

  @Test
  void namesTheChecksThatAreDown() {
    // as in the response of the /health/ready endpoint of Keycloak
    JsonObject report = Json.createObjectBuilder()
        .add("status", "DOWN")
        .add("checks", Json.createArrayBuilder()
            .add(Json.createObjectBuilder().add("name", "Keycloak database connections health check").add("status", "DOWN"))
            .add(Json.createObjectBuilder().add("name", "Keycloak cluster health check").add("status", "UP"))
            .add(Json.createObjectBuilder().add("name", "Keycloak cache health check").add("status", "DOWN")))
        .build();

    assertThat(HealthReadiness.down(report)).containsExactly("Keycloak database connections health check", "Keycloak cache health check");
  }

  @Test
  void namesNoChecksWithoutThem() {
    assertThat(HealthReadiness.down(Json.createObjectBuilder().add("status", "DOWN").build())).isEmpty();
  }
}
