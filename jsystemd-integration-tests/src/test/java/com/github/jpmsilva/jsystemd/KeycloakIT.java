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
import java.util.List;
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

  // the status of the JVM, without a stage once ready, the watchdog heartbeats, and a clean stop
  private void assertRunning() {
    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText")).startsWith("Heap: "));

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
