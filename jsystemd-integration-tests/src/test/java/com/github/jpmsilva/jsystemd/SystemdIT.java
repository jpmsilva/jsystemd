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
 * Runs the fixture application, or the Spring Boot sample application, as a systemd service, and checks how systemd sees it. Each test runs its
 * own service, named after the test.
 */
class SystemdIT {

  private static final SystemdContainer container = new SystemdContainer();

  // the journal of the service of a failed test, which tells what the application sent, and what systemd did
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
    Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
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

  // so that the services of the tests do not compete, such as for the port of the Spring Boot sample application
  @AfterEach
  void tearDown() {
    container.stopUnit(unit);
  }

  @Test
  void readyAndStatus() {
    container.runFixture(unit, List.of(), "startup=PT5S");

    // the status is sent while starting up, before ready
    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "activating")
        .containsEntry("StatusText", "phase=starting"));
    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("SubState", "running")
        .containsEntry("StatusText", "phase=running"));
    assertExpectedChannel();
  }

  @Test
  void statusInUtf8() {
    // in a locale whose encoding is not UTF-8, such as the C locale, whose encoding is ASCII, as the status must still be sent in UTF-8, which systemd
    // expects, and ignores a status that is not; recent versions of systemd run services in the C.UTF-8 locale when none is configured
    container.runFixture(unit, List.of("Environment=LANG=C"), "non-ascii-status=true");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("StatusText", "phase=running, " + FixtureApplication.NON_ASCII_STATUS));
  }

  @Test
  void startupTimeoutExtended() {
    container.runFixture(unit, List.of("TimeoutStartSec=3"), "startup=PT8S", "extend-timeout=true");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("SubState", "running"));
  }

  @Test
  void startupTimeoutNotExtended() {
    // the counterpart of the test above, which shows that the service only starts because the timeout is extended
    container.runFixture(unit, List.of("TimeoutStartSec=3"), "startup=PT8S");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "timeout"));
  }

  @Test
  void stoppingDuringStartup() {
    // the startup is extended past TimeoutStartSec, until the application is told to stop while still starting up
    container.runFixture(unit, List.of("TimeoutStartSec=4", "SuccessExitStatus=143"), "startup=PT60S", "extend-timeout=true", "shutdown=PT15S");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "activating"));
    await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(9))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "activating"));

    container.kill(unit, "SIGTERM");

    // systemd keeps the service activating, as STOPPING=1 does not end a startup, so it still enforces TimeoutStartSec: the periodic extension of the
    // startup stops once the application is stopping, so that a hung shutdown times out; the startup times out well before this shutdown would complete,
    // and systemd then stops the service
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "deactivating")
        .containsEntry("Result", "timeout"));
  }

  @Test
  void noHeartbeatsDuringStartup() {
    // killed with SIGKILL rather than the default SIGABRT, so that the JVM does not spend time writing a crash report
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL"), "startup=PT8S", "unhealthy-during-startup-after=PT3S");

    // no heartbeat is sent before the application is ready, so that systemd does not enforce the watchdog while it starts up, even when it reports itself
    // as unhealthy for longer than WatchdogSec while starting up
    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("SubState", "running"));
  }

  @Test
  void heartbeatsDuringStartup() {
    // the counterpart of the test above, which shows why no heartbeat is sent before ready: a heartbeat sent while starting up makes systemd enforce the
    // watchdog, and kill the application once the heartbeats stop, although it is still starting up
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL"), "startup=PT8S", "heartbeats-during-startup=PT3S");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "watchdog"));
  }

  @Test
  void watchdogHeartbeats() {
    container.runFixture(unit, List.of("WatchdogSec=2"));
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));
    String firstHeartbeat = container.show(unit).get("WatchdogTimestampMonotonic");

    // systemd records each heartbeat, and keeps the service running over several watchdog intervals
    await().untilAsserted(() -> assertThat(container.show(unit).get("WatchdogTimestampMonotonic")).isNotEqualTo(firstHeartbeat));
    await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(9))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));
  }

  @Test
  void watchdogHeartbeatsSuppressedWhenUnhealthy() {
    // killed with SIGKILL rather than the default SIGABRT, so that the JVM does not spend time writing a crash report
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL"), "unhealthy-after=PT3S");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "watchdog"));
  }

  @Test
  void slowShutdownAfterStopping() {
    // killed with SIGKILL, as above; the JVM exits with 143 after SIGTERM, which is a clean exit
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL", "SuccessExitStatus=143"), "shutdown=PT12S");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    // a shutdown started outside of systemd, as by the application itself: systemd keeps enforcing the watchdog until told the application is stopping
    container.kill(unit, "SIGTERM");

    // told the application is stopping, systemd waits for it over several watchdog intervals without heartbeats, until it exits cleanly
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(9))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }

  @Test
  void slowShutdownAfterClosing() {
    // closing the instance stops the heartbeats, but also tells systemd that the application is stopping, if it was not told before
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL", "SuccessExitStatus=143"), "shutdown=PT12S", "on-shutdown=close");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    container.kill(unit, "SIGTERM");

    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(9))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }

  @Test
  void slowShutdownWithoutStopping() {
    // the counterpart of the tests above, which shows that the application is only left to shut down because systemd is told it is stopping
    container.runFixture(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL", "SuccessExitStatus=143"), "shutdown=PT8S", "on-shutdown=unhealthy");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    container.kill(unit, "SIGTERM");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "watchdog"));
  }

  @Test
  void shutdownTimeoutExtended() {
    container.runFixture(unit, List.of("TimeoutStopSec=3", "SuccessExitStatus=143"), "shutdown=PT12S", "extend-timeout-on-shutdown=true");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    container.kill(unit, "SIGTERM");

    // the shutdown takes longer than TimeoutStopSec, but each extension gives it more time, so it completes cleanly
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }

  @Test
  void shutdownTimeoutNotExtended() {
    // the counterpart of the test above, which shows that the shutdown only completes because the timeout is extended
    container.runFixture(unit, List.of("TimeoutStopSec=3", "SuccessExitStatus=143"), "shutdown=PT8S");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    container.kill(unit, "SIGTERM");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "timeout"));
  }

  @Test
  void springBootSample() {
    container.runSample(unit, List.of("WatchdogSec=2"));
    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("SubState", "running"));

    // the status of all the built-in status providers, in their order, with the default settings of Spring Boot
    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText")).matches(
        sampleStatus("http-nio-8080: \\d+/8192 connections, \\d+/\\d+ threads", ", applicationTaskExecutor: \\d+/\\d+ threads, \\d+ queued")));

    String firstHeartbeat = container.show(unit).get("WatchdogTimestampMonotonic");
    await().untilAsserted(() -> assertThat(container.show(unit).get("WatchdogTimestampMonotonic")).isNotEqualTo(firstHeartbeat));
    assertExpectedChannel();
  }

  @Test
  void springBootSampleUnderVirtualThreads() {
    container.runSample(unit, List.of(), "--spring.threads.virtual.enabled=true");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    // virtual threads are not pooled, so Tomcat only reports its connections, and the task executor is left out
    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText")).matches(sampleStatus("http-nio-8080: \\d+/8192 connections", "")));
  }

  @Test
  void springBootSampleSlowShutdown() {
    // killed with SIGKILL if the watchdog expires; the JVM exits with 143 after SIGTERM, which is a clean exit
    container.runSample(unit, List.of("WatchdogSec=2", "WatchdogSignal=SIGKILL", "SuccessExitStatus=143"), "--sample.shutdown=12s");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "active"));

    // a shutdown started outside of systemd: the starter tells systemd that the application is stopping as soon as the application context starts closing,
    // so systemd no longer enforces the watchdog, and waits for the beans to be destroyed over several watchdog intervals without heartbeats
    container.kill(unit, "SIGTERM");

    // the stage of the shutdown comes first in the status
    await().untilAsserted(() -> {
      assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating");
      assertThat(container.show(unit).get("StatusText")).startsWith("Shutdown: destroying beans");
    });
    await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(7))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "deactivating"));
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }

  @Test
  void springBootSampleEndlessRunner() {
    container.runSample(unit, List.of(), "--sample.endless-runner=true");

    // by default, the application is only ready once it accepts traffic, after the runners have run, so it keeps starting up while one runs
    await().untilAsserted(() -> assertThat(container.show(unit).get("StatusText")).startsWith("State: started"));
    await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
        .untilAsserted(() -> assertThat(container.show(unit)).containsEntry("ActiveState", "activating"));
  }

  @Test
  void springBootSampleEndlessRunnerReadyOnceStarted() {
    container.runSample(unit, List.of("WatchdogSec=2", "SuccessExitStatus=143"), "--sample.endless-runner=true",
        "--systemd.ready-event=application-started");

    // the counterpart of the test above: the application is ready before the runners run
    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "active")
        .containsEntry("SubState", "running"));
    String firstHeartbeat = container.show(unit).get("WatchdogTimestampMonotonic");
    await().untilAsserted(() -> assertThat(container.show(unit).get("WatchdogTimestampMonotonic")).isNotEqualTo(firstHeartbeat));

    // and still stops while the runner runs
    container.kill(unit, "SIGTERM");
    await().untilAsserted(() -> assertThat(container.show(unit)).containsEntry("LoadState", "not-found"));
  }

  @Test
  void springBootSampleFailedStartup() {
    // a startup timeout far longer than the wait below: the service fails as soon as the application does, rather than once the timeout expires
    container.runSample(unit, List.of("TimeoutStartSec=120"), "--sample.fail-startup=true");

    await().untilAsserted(() -> assertThat(container.show(unit))
        .containsEntry("ActiveState", "failed")
        .containsEntry("Result", "exit-code"));
  }

  /**
   * Checks that the application notified systemd as expected on the JDK it runs on, which shows that the multi-release jar of the library is used as such
   * however the application is packaged, and that the JVM did not warn that the library calls native code, as on Java 22 and later, unless native access is
   * enabled, as documented, which the applications do: the fixture application with {@code --enable-native-access}, and the Spring Boot sample application
   * in its manifest.
   */
  private void assertExpectedChannel() {
    String journal = container.journal(unit);
    assertThat(journal).contains(FixtureImage.expectedChannel());
    assertThat(journal).doesNotContain("restricted method");
  }

  /**
   * The pattern of the status of the Spring Boot sample application once ready, with the given patterns of the Tomcat status and of the task executor
   * status. The old generation is only reported once it is first collected, which may not have happened yet.
   */
  private static String sampleStatus(String tomcat, String taskExecutor) {
    String bytes = "[0-9.,]+ [KMGT]?i?B";
    return "health status: healthy, Liveness: correct; readiness: accepting traffic, Heap: " + bytes + "/" + bytes + ", (Old gen after GC: " + bytes + "/"
        + bytes + ", )?Cgroup memory: " + bytes + "(/" + bytes + ")?, Threads: \\d+ \\([^)]+\\), File descriptors: \\d+/\\d+, " + tomcat + taskExecutor
        + ", Non-heap: " + bytes + "/" + bytes + ", Buffers: " + bytes + "/" + bytes + " direct \\(\\d+\\)(; " + bytes + " mapped \\(\\d+\\))?, Classes: \\d+";
  }
}
