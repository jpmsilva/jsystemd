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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Runs Keycloak, with its provider for systemd, as a systemd service, and checks how systemd sees it. Keycloak is the release the build sets in
 * {@code org.keycloak.version}. Each test builds Keycloak with its own options, and runs its own service, named after the test.
 */
class KeycloakIT {

  private static final SystemdContainer container = new SystemdContainer();

  // the service unit properties of every test: the JVM exits with 143 after SIGTERM, which is a clean exit
  private static final List<String> PROPERTIES = List.of("WatchdogSec=2", "SuccessExitStatus=143", "TimeoutStartSec=180");

  // the journal of the service of a failed test, which tells what Keycloak and the provider logged, and what systemd did
  @RegisterExtension
  static final TestWatcher journalOnFailure = new TestWatcher() {
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
      System.err.println(container.journal(context.getRequiredTestMethod().getName()));
    }
  };

  private String unit;

  @BeforeAll
  static void startContainer() {
    container.start();
    // Keycloak takes a few seconds to start, and longer on its first start, which creates its database
    Awaitility.setDefaultTimeout(Duration.ofMinutes(3));
    Awaitility.setDefaultPollInterval(Duration.ofMillis(200));
  }

  @AfterAll
  static void stopContainer() {
    container.stop();
    Awaitility.reset();
  }

  @BeforeEach
  void setUp(TestInfo testInfo) {
    unit = testInfo.getTestMethod().orElseThrow().getName();
  }

  // so that the services of the tests do not compete for the ports of Keycloak
  @AfterEach
  void tearDown() {
    container.stopUnit(unit);
  }

  @Test
  void readyOnceTheReadinessHealthChecksAreUp() {
    // Keycloak listens first, and bootstraps afterwards, answering 503 until then
    container.buildKeycloak("--health-enabled=true");
    container.runKeycloak(unit, PROPERTIES);
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    // ready for systemd means ready for a load balancer, and serving requests
    assertThat(container.httpStatus("http://localhost:9000/health/ready")).isEqualTo("200");
    assertThat(container.httpStatus("http://localhost:8080/realms/master")).isEqualTo("200");

    assertRunning();
  }

  @Test
  void readyOnceListeningWithoutTheHealthEndpoints() {
    // Keycloak bootstraps first, and listens afterwards
    container.buildKeycloak();
    container.runKeycloak(unit, PROPERTIES);
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    assertThat(container.httpStatus("http://localhost:8080/realms/master")).isEqualTo("200");

    assertRunning();
  }

  @Test
  void followsTheDatabaseMigration() {
    // Keycloak only migrates its database when it has changesets to apply, as the first time it starts, which creates it
    container.removeKeycloakDatabase();
    container.buildKeycloak();
    // systemd then logs the notifications it receives
    container.logLevel("debug");
    try {
      container.runKeycloak(unit, PROPERTIES);
      List<String> statuses = new ArrayList<>();
      await().untilAsserted(() -> {
        Map<String, String> properties = container.show(unit);
        statuses.add(properties.get("StatusText"));
        assertThat(properties).containsEntry("ActiveState", "active");
      });

      // the provider is the Liquibase connection provider of Keycloak, without any option, and each changeset extends the startup timeout
      String journal = container.journal(unit);
      assertThat(journal).contains("Following the database migration of Keycloak");
      assertThat(journal).contains("EXTEND_TIMEOUT_USEC=");
      assertThat(statuses).anyMatch(status -> status.startsWith("Startup: migrating the database (changeset "));
    } finally {
      container.logLevel("info");
    }
  }

  @Test
  void showsTheDatasourceAndTakesTheHealthIntoAccount() {
    // the connection pool of the datasource only counts its connections with the metrics of Keycloak, and so do its readiness health checks check it
    container.buildKeycloak("--health-enabled=true", "--metrics-enabled=true");
    container.runKeycloak(unit, PROPERTIES, "--spi-systemd--default--threads-deadlock-detection=true",
        "--spi-systemd--default--threads-deadlock-health=true", "--spi-systemd--default--file-descriptors-health=true",
        "--spi-systemd--default--datasource-health=true", "--spi-systemd--default--readiness-health=true",
        "--spi-systemd--default--status-providers=cluster,datasource,heap,version");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText"))
        .matches("Cluster: 1 node, Heap: .*, Datasource: \\d+/100 connections, 0 waiting, Keycloak \\d+\\.\\d+\\.\\d+"));

    // none of the health conditions holds, so the heartbeats keep coming, long enough for each health provider to be asked a few times
    String firstHeartbeat = container.show(unit).get("WatchdogTimestampMonotonic");
    await().pollDelay(Duration.ofSeconds(8)).untilAsserted(() -> {
      Map<String, String> properties = container.show(unit);
      assertThat(properties).containsEntry("ActiveState", "active");
      assertThat(properties.get("WatchdogTimestampMonotonic")).isNotEqualTo(firstHeartbeat);
    });
    assertThat(container.journal(unit)).doesNotContain("Suppressing heartbeat");
  }

  // the status of Keycloak and of the JVM, without a stage once ready, the watchdog heartbeats, and a clean stop
  private void assertRunning() {
    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText")).startsWith("Cluster: 1 node, Heap: "));

    String firstHeartbeat = container.show(unit).get("WatchdogTimestampMonotonic");
    await().untilAsserted(() -> assertThat(container.show(unit).get("WatchdogTimestampMonotonic")).isNotEqualTo(firstHeartbeat));
    // the provider jar is used as a multi-release jar by Keycloak, and kc.sh enables native access itself, so the JVM does not warn that the provider calls
    // native code, as on Java 22 and later
    String journal = container.journal(unit);
    assertThat(journal).contains(FixtureImage.expectedChannel());
    assertThat(journal).doesNotContain("restricted method");

    // systemd forgets a transient service once it stops cleanly
    container.stopUnit(unit);
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }
}
