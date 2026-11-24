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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * A container that boots systemd, in which the fixture application, the Spring Boot sample application, and Keycloak run as transient services.
 */
class SystemdContainer extends GenericContainer<SystemdContainer> {

  // the properties shown by show(), enough to follow the startup, the status and the watchdog of a service
  // LoadState is not-found once systemd forgets a transient service, which it does as soon as the service stops cleanly
  private static final List<String> PROPERTIES = List.of("LoadState", "ActiveState", "SubState", "Result", "StatusText", "WatchdogTimestampMonotonic");

  // shared by the test classes, which run in the same JVM, so that the image, whose context includes the whole Keycloak distribution, is built once
  private static final ImageFromDockerfile IMAGE = FixtureImage.of("systemd").withFileFromPath("sample.jar", FixtureImage.path("fixture.sample"))
      .withFileFromPath("keycloak", FixtureImage.path("fixture.keycloak"))
      .withFileFromPath("keycloak-provider.jar", FixtureImage.path("fixture.keycloak-provider"));

  SystemdContainer() {
    super(IMAGE);
    // systemd needs to manage cgroups, and its own tmpfs mounts
    withPrivilegedMode(true);
    withTmpFs(Map.of("/run", "rw", "/run/lock", "rw", "/tmp", "rw"));
  }

  @Override
  public void start() {
    super.start();
    // degraded is enough: some unit that does not matter to the tests may fail in a container
    await("systemd to boot").atMost(Duration.ofMinutes(1))
        .until(() -> exec("systemctl", "is-system-running").getStdout().trim(), state -> state.equals("running") || state.equals("degraded"));
  }

  /**
   * Runs the fixture application as a transient service, without waiting for it to start.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @param properties the properties of the service unit, in the form {@code name=value}, besides <code>Type=notify</code>
   * @param args the options of the fixture application, in the form {@code name=value}
   */
  void runFixture(String unit, List<String> properties, String... args) {
    // with native access enabled, as documented, so that the JVM does not warn on Java 22 and later
    run(unit, properties, List.of("java", "--enable-native-access=ALL-UNNAMED", "-cp", "/opt/fixture/classes:/opt/fixture/lib/*",
        FixtureApplication.class.getName()), args);
  }

  /**
   * Runs the Spring Boot sample application as a transient service, without waiting for it to start. Its manifest enables native access, as documented.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @param properties the properties of the service unit, in the form {@code name=value}, besides <code>Type=notify</code>
   * @param args the arguments of the Spring Boot sample application, such as {@code --name=value} to set Spring Boot properties
   */
  void runSample(String unit, List<String> properties, String... args) {
    run(unit, properties, List.of("java", "-jar", "/opt/sample/sample.jar"), args);
  }

  /**
   * Builds Keycloak, with its provider for systemd, and waits for the build to complete.
   *
   * @param options the build options of Keycloak, such as {@code --health-enabled=true}
   * @return the output of the build
   */
  String buildKeycloak(String... options) {
    List<String> command = new ArrayList<>(List.of("/opt/keycloak/bin/kc.sh", "build"));
    command.addAll(List.of(options));
    ExecResult result = exec(command.toArray(String[]::new));
    assertThat(result.getExitCode()).as("kc.sh build failed: %s%s", result.getStdout(), result.getStderr()).isZero();
    return result.getStdout() + result.getStderr();
  }

  /**
   * Runs Keycloak, as built by {@link #buildKeycloak(String...)}, as a transient service, without waiting for it to start. It listens for HTTP on port
   * 8080, and on port 9000 for its management interface, such as its health endpoints when enabled.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @param properties the properties of the service unit, in the form {@code name=value}, besides <code>Type=notify</code> and
   *     <code>NotifyAccess=all</code>, as Keycloak runs as a child process of {@code kc.sh}
   * @param options the options Keycloak starts with, such as those of its provider for systemd
   */
  void runKeycloak(String unit, List<String> properties, String... options) {
    List<String> all = new ArrayList<>(List.of("NotifyAccess=all"));
    all.addAll(properties);
    run(unit, all, List.of("/opt/keycloak/bin/kc.sh", "start", "--optimized", "--http-enabled=true", "--hostname-strict=false"), options);
  }

  /**
   * Removes the database of Keycloak, which then creates it again the next time it starts, migrating it from scratch.
   */
  void removeKeycloakDatabase() {
    ExecResult result = exec("rm", "-rf", "/opt/keycloak/data/h2");
    assertThat(result.getExitCode()).as("removing the database of Keycloak failed: %s", result.getStderr()).isZero();
  }

  /**
   * Sets how much systemd logs: at the {@code debug} level, it logs every notification it receives from a service, in the journal of the service.
   *
   * @param level the log level, such as {@code debug} or {@code info}
   */
  void logLevel(String level) {
    ExecResult result = exec("systemctl", "log-level", level);
    assertThat(result.getExitCode()).as("systemctl log-level failed: %s", result.getStderr()).isZero();
  }

  /**
   * Requests a URL from within the container.
   *
   * @param url the URL
   * @return the HTTP status of the response, or {@code 000} when there is none, as when nothing listens
   */
  String httpStatus(String url) {
    return exec("curl", "--silent", "--output", "/dev/null", "--write-out", "%{http_code}", url).getStdout();
  }

  private void run(String unit, List<String> properties, List<String> application, String... args) {
    List<String> command = new ArrayList<>(List.of("systemd-run", "--no-block", "--unit=" + unit, "--property=Type=notify"));
    properties.forEach(property -> command.add("--property=" + property));
    command.addAll(application);
    command.addAll(List.of(args));
    ExecResult result = exec(command.toArray(String[]::new));
    assertThat(result.getExitCode()).as("systemd-run failed: %s", result.getStderr()).isZero();
  }

  /**
   * Sends a signal to the main process of a service unit, as if sent by something other than systemd: systemd does not stop the service, it only notices
   * when the process says it is stopping, or exits.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @param signal the signal, such as {@code SIGTERM}
   */
  void kill(String unit, String signal) {
    ExecResult result = exec("systemctl", "kill", "--kill-whom=main", "--signal=" + signal, unit + ".service");
    assertThat(result.getExitCode()).as("systemctl kill failed: %s", result.getStderr()).isZero();
  }

  /**
   * Stops a service unit, if still running, and waits for it to stop.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   */
  void stopUnit(String unit) {
    exec("systemctl", "stop", unit + ".service");
  }

  /**
   * Shows the properties of a service unit that the tests check, as systemd sees them.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @return the properties, by name
   */
  Map<String, String> show(String unit) {
    List<String> command = new ArrayList<>(List.of("systemctl", "show", unit + ".service"));
    PROPERTIES.forEach(property -> command.add("--property=" + property));
    Map<String, String> properties = new LinkedHashMap<>();
    exec(command.toArray(String[]::new)).getStdout().lines().forEach(line -> {
      int separator = line.indexOf('=');
      properties.put(line.substring(0, separator), line.substring(separator + 1));
    });
    return properties;
  }

  /**
   * Shows the journal of a service unit, to help understand why a test failed.
   *
   * @param unit the name of the service unit, without the {@code .service} suffix
   * @return the journal
   */
  String journal(String unit) {
    return exec("journalctl", "--no-pager", "--unit=" + unit + ".service").getStdout();
  }

  private ExecResult exec(String... command) {
    try {
      return execInContainer(command);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
