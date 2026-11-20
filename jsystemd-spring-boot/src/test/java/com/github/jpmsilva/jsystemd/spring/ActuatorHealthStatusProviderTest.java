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
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;

/**
 * Tests for {@link ActuatorHealthStatusProvider}.
 *
 * @author Joao Silva
 */
class ActuatorHealthStatusProviderTest {

  private static DefaultHealthContributorRegistry registry(Map<String, ? extends HealthContributor> contributors) {
    DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
    contributors.forEach(registry::registerContributor);
    return registry;
  }

  private static ActuatorHealthStatusProvider provider(Map<String, ? extends HealthContributor> contributors) {
    return new ActuatorHealthStatusProvider(registry(contributors), Set.of(Status.DOWN), name -> true, true);
  }

  @Test
  void isAlwaysHealthyForTheWatchdogWhenHealthIsDisabledButShowsTheActualHealthInTheStatus() {
    ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(registry(Map.of("down", (HealthIndicator) () -> Health.down().build())),
        Set.of(Status.DOWN), name -> true, false);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    assertThat(provider.status()).isEqualTo("health status: unhealthy (down)");
  }

  @Test
  void isHealthyWhenNoIndicatorIsDown() {
    HealthProvider.Health health = provider(Map.of("up", (HealthIndicator) () -> Health.up().build())).health();

    assertThat(health.healthy()).isTrue();
    assertThat(health.details()).isEmpty();
  }

  @Test
  void reportsDetailsOfEachUnhealthyIndicatorUnderItsName() {
    Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
    indicators.put("first", () -> Health.down().withDetail("error", "first").build());
    indicators.put("up", () -> Health.up().withDetail("error", "ignored").build());
    indicators.put("second", () -> Health.down().withDetail("error", "second").withDetail("other", "value").build());

    HealthProvider.Health health = provider(indicators).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsOnly(
        entry("first", Map.of("error", "first")),
        entry("second", Map.of("error", "second", "other", "value")));
  }

  @Test
  void isUnhealthyWhenIndicatorWithoutDetailsIsDown() {
    HealthProvider.Health health = provider(Map.of("down", (HealthIndicator) () -> Health.down().build())).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsExactly(entry("down", Map.of()));
  }

  @Test
  void reportsTheUnhealthyMembersOfACompositeUnderItsName() {
    Map<String, HealthIndicator> members = new LinkedHashMap<>();
    members.put("primary", () -> Health.up().build());
    members.put("secondary", () -> Health.down().withDetail("error", "refused").build());

    ActuatorHealthStatusProvider provider = provider(Map.of("db", CompositeHealthContributor.fromMap(members)));

    assertThat(provider.health().details()).containsExactly(entry("db", Map.of("secondary", Map.of("error", "refused"))));
    assertThat(provider.status()).isEqualTo("health status: unhealthy (db)");
  }

  @Test
  void isHealthyWhenAllMembersOfACompositeAreHealthy() {
    ActuatorHealthStatusProvider provider = provider(Map.of("db", CompositeHealthContributor.fromMap(Map.of("primary", (HealthIndicator) () ->
        Health.up().build()))));

    assertThat(provider.health().healthy()).isTrue();
  }

  @Test
  void onlyTakesTheMembersIntoAccount() {
    Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
    indicators.put("db", () -> Health.down().build());
    indicators.put("diskSpace", () -> Health.down().build());

    ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(registry(indicators), Set.of(Status.DOWN), "diskSpace"::equals, true);

    assertThat(provider.health().details()).containsOnlyKeys("diskSpace");
    assertThat(provider.status()).isEqualTo("health status: unhealthy (diskSpace)");
  }

  @Test
  void testsTheMembersOfACompositeByTheirPath() {
    Map<String, HealthIndicator> members = new LinkedHashMap<>();
    members.put("primary", () -> Health.down().withDetail("error", "primary").build());
    members.put("secondary", () -> Health.down().withDetail("error", "secondary").build());
    List<String> tested = new ArrayList<>();

    ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(registry(Map.of("db", CompositeHealthContributor.fromMap(members))),
        Set.of(Status.DOWN), path -> tested.add(path) && path.equals("db/secondary"), true);

    assertThat(provider.health().details()).containsExactly(entry("db", Map.of("secondary", Map.of("error", "secondary"))));
    assertThat(tested).containsExactly("db/primary", "db/secondary");
  }

  @Test
  void isHealthyWhenOnlyIndicatorsThatAreNotMembersAreUnhealthy() {
    ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(registry(Map.of("db", (HealthIndicator) () -> Health.down().build())),
        Set.of(Status.DOWN), name -> false, true);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    assertThat(provider.status()).isEqualTo("health status: healthy");
  }

  @Test
  void doesNotEvaluateIndicatorsThatAreNotMembers() {
    AtomicInteger invocations = new AtomicInteger();

    new ActuatorHealthStatusProvider(registry(Map.of("db", (HealthIndicator) () -> {
      invocations.incrementAndGet();
      return Health.down().build();
    })), Set.of(Status.DOWN), name -> false, true).health();

    assertThat(invocations).hasValue(0);
  }

  @Test
  void showsTheHealthOnlyWhileRunning() throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    // as the health indicator of a connection pool already closed while the application stops
    ActuatorHealthStatusProvider provider = provider(Map.of("redis", (HealthIndicator) () -> {
      invocations.incrementAndGet();
      return Health.down().build();
    }));
    try (MockedStatic<SystemdNotify> notify = Mockito.mockStatic(SystemdNotify.class)) {
      SystemdProcess.reset();
      Systemd systemd = Systemd.instance();
      try {
        systemd.addStatusProviders(provider);

        systemd.updateStatus();
        systemd.ready();
        systemd.stopping();
        systemd.updateStatus();
      } finally {
        systemd.close();
      }

      // the indicators are only evaluated once ready, on the status update that ready() sends
      notify.verify(SystemdProcess.status(() -> "health status: unhealthy (redis)"));
      notify.verify(SystemdProcess.status(() -> ""), Mockito.times(2));
      assertThat(invocations).hasValue(1);
    } finally {
      SystemdProcess.reset();
    }
  }

  @Test
  void evaluatesEachIndicatorOnce() {
    AtomicInteger invocations = new AtomicInteger();

    provider(Map.of("down", (HealthIndicator) () -> {
      invocations.incrementAndGet();
      return Health.down().build();
    })).health();

    assertThat(invocations).hasValue(1);
  }

  @Test
  void ignoresIndicatorsWithoutHealth() {
    HealthProvider.Health health = provider(Map.of("none", (HealthIndicator) () -> null)).health();

    assertThat(health.healthy()).isTrue();
  }

  @Test
  void takesContributorsRegisteredLaterIntoAccount() {
    DefaultHealthContributorRegistry registry = registry(Map.of());
    ActuatorHealthStatusProvider provider = new ActuatorHealthStatusProvider(registry, Set.of(Status.DOWN), name -> true, true);
    registry.registerContributor("down", (HealthIndicator) () -> Health.down().build());

    assertThat(provider.health().healthy()).isFalse();
  }

  @Test
  void considersAnIndicatorThatThrowsDownAndStillEvaluatesTheOthers() {
    Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
    indicators.put("throwing", () -> {
      throw new IllegalStateException("connection refused");
    });
    indicators.put("down", () -> Health.down().build());

    HealthProvider.Health health = provider(indicators).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsOnlyKeys("throwing", "down");
    assertThat(health.details().get("throwing")).isEqualTo(Map.of("error", "java.lang.IllegalStateException: connection refused"));
  }

  @Test
  void statusNamesAnIndicatorThatThrows() {
    assertThat(provider(Map.of("throwing", (HealthIndicator) () -> {
      throw new IllegalStateException("connection refused");
    })).status()).isEqualTo("health status: unhealthy (throwing)");
  }

  @Test
  void statusIsHealthyWhenNoIndicatorIsDown() {
    assertThat(provider(Map.of("up", (HealthIndicator) () -> Health.up().build())).status()).isEqualTo("health status: healthy");
  }

  @Test
  void statusNamesUnhealthyIndicatorsWithoutTheirDetails() {
    Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
    indicators.put("db", () -> Health.down().withDetail("error", "Connection to db.internal:5432 refused").build());
    indicators.put("diskSpace", () -> Health.up().withDetail("path", "/srv/app").build());
    indicators.put("redis", () -> Health.down().withDetail("error", "redis.internal:6379 unreachable").build());

    String status = provider(indicators).status();

    assertThat(status).isEqualTo("health status: unhealthy (db, redis)");
    assertThat(status).doesNotContain("internal", "error", "/srv/app");
  }
}
