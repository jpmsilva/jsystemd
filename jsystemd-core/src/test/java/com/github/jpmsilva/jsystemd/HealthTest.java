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

package com.github.jpmsilva.jsystemd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.github.jpmsilva.jsystemd.HealthProvider.Health;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Health}.
 *
 * @author Joao Silva
 */
class HealthTest {

  @Test
  void healthyHasNoDetails() {
    assertThat(Health.HEALTHY.healthy()).isTrue();
    assertThat(Health.HEALTHY.details()).isEmpty();
  }

  @Test
  void detailsCannotBeModified() {
    // a modifiable map, so that it is the health that prevents the modifications
    Map<String, Object> details = new HashMap<>();
    details.put("db", "down");
    Health health = new Health(false, details);

    assertThatThrownBy(() -> health.details().put("cache", "down")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> health.details().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void isNotAffectedByLaterChangesToTheDetails() {
    Map<String, Object> details = new HashMap<>();
    details.put("db", "down");
    Health health = new Health(false, details);

    details.put("cache", "down");

    assertThat(health.details()).containsOnly(entry("db", "down"));
  }

  @Test
  void keepsTheOrderOfTheDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("redis", "down");
    details.put("db", "down");
    details.put("cache", "down");

    assertThat(new Health(false, details).details()).containsExactly(entry("redis", "down"), entry("db", "down"), entry("cache", "down"));
  }

  @Test
  void rejectsMissingDetails() {
    assertThatThrownBy(() -> new Health(false, null)).isInstanceOf(NullPointerException.class).hasMessage("Details must not be null");
  }

  @Test
  void allowsDetailsWithoutValue() {
    Map<String, Object> details = new HashMap<>();
    details.put("db", null);

    assertThat(new Health(false, details).details()).containsOnly(entry("db", null));
  }
}
