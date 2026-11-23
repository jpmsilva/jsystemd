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

package com.github.jpmsilva.jsystemd.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.github.jpmsilva.jsystemd.HealthProvider;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;

/**
 * Tests for {@link AvailabilityStatusProvider}.
 *
 * @author Joao Silva
 */
class AvailabilityStatusProviderTest {

  private final AvailabilityStatusProvider provider = new AvailabilityStatusProvider(false);

  private void publish(AvailabilityState state) {
    provider.onApplicationEvent(new AvailabilityChangeEvent<>(this, state));
  }

  @Test
  void reportsNothingUntilAStateIsPublished() {
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsTheLatestLivenessAndReadinessStates() {
    publish(LivenessState.CORRECT);
    publish(ReadinessState.ACCEPTING_TRAFFIC);

    assertThat(provider.status()).isEqualTo("Liveness: correct; readiness: accepting traffic");

    publish(LivenessState.BROKEN);
    publish(ReadinessState.REFUSING_TRAFFIC);

    assertThat(provider.status()).isEqualTo("Liveness: broken; readiness: refusing traffic");
  }

  @Test
  void reportsEachStateOnceFirstPublished() {
    publish(ReadinessState.REFUSING_TRAFFIC);

    assertThat(provider.status()).isEqualTo("Readiness: refusing traffic");
  }

  @Test
  void ignoresOtherStates() {
    publish(LivenessState.CORRECT);
    publish(new AvailabilityState() {
    });

    assertThat(provider.status()).isEqualTo("Liveness: correct");
  }

  @Test
  void isAlwaysHealthyWhenHealthIsDisabled() {
    publish(LivenessState.BROKEN);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isUnhealthyWhileTheLivenessStateIsBroken() {
    AvailabilityStatusProvider health = new AvailabilityStatusProvider(true);
    health.onApplicationEvent(new AvailabilityChangeEvent<>(this, LivenessState.CORRECT));
    assertThat(health.health()).isEqualTo(HealthProvider.Health.HEALTHY);

    health.onApplicationEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
    assertThat(health.health().healthy()).isFalse();
    assertThat(health.health().details()).containsExactly(entry("livenessState", "BROKEN"));
    // Spring Boot defines the broken state as one the application cannot recover from, so the watchdog is triggered right away
    assertThat(health.health().unrecoverable()).isTrue();

    health.onApplicationEvent(new AvailabilityChangeEvent<>(this, LivenessState.CORRECT));
    assertThat(health.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyWhileOnlyRefusingTraffic() {
    AvailabilityStatusProvider health = new AvailabilityStatusProvider(true);

    health.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));

    assertThat(health.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void delaysTheUnhealthyStateWhenDelayIsSet() {
    AvailabilityStatusProvider health = new AvailabilityStatusProvider(true);
    health.setUnhealthyDelay(Duration.ofMinutes(1));

    health.onApplicationEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));

    assertThat(health.health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void describesTheStatesRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under a Turkish locale, an uppercase "I" is lowercased to a dotless "ı"
    Locale.setDefault(Locale.forLanguageTag("tr"));
    try {
      publish(ReadinessState.ACCEPTING_TRAFFIC);

      assertThat(provider.status()).isEqualTo("Readiness: accepting traffic");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }
}
