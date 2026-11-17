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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Runs the fixture application where systemd expects notifications, but libsystemd is missing, as on Alpine Linux. There is no systemd to ask, so the
 * application reports itself whether the integration is usable, and why not, and the notifications it receives are printed by the container.
 */
class NoLibsystemdIT {

  @Test
  void runsWithoutLibsystemd() {
    try (GenericContainer<?> container = new GenericContainer<>(FixtureImage.of("no-libsystemd"))
        .waitingFor(Wait.forLogMessage(".*Started.*\\n", 1).withStartupTimeout(Duration.ofSeconds(30)))) {
      container.start();

      if (FixtureImage.jdk() >= 22) {
        // sent without libsystemd, nor JNA
        assertThat(container.getLogs()).contains("Usable: true, load failure: none").contains(FixtureImage.expectedChannel());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(container.getLogs()).contains("READY=1"));
        assertThat(container.getLogs()).doesNotContain("restricted method");
      } else {
        assertThat(container.getLogs()).contains("Usable: false, load failure: Unable to load library 'systemd'");
        // the notifications, such as the periodic status updates, are skipped without failing the application
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(container::isRunning);
        assertThat(container.getLogs()).doesNotContain("READY=1");
      }
    }
  }
}
