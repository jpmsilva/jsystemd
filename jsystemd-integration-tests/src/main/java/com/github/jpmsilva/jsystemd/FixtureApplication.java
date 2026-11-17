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

import static org.slf4j.LoggerFactory.getLogger;

import com.github.jpmsilva.jsystemd.HealthProvider.Health;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

/**
 * The application the integration tests run as a systemd service. Its behaviour is set by its arguments, each in the form {@code name=value}:
 * <ul>
 *   <li>{@code startup}: how long the startup takes, as an ISO-8601 duration (default {@code PT0S});</li>
 *   <li>{@code extend-timeout}: {@code true} to periodically extend the startup timeout while starting up (default {@code false});</li>
 *   <li>{@code unhealthy-after}: how long after being ready the application reports itself as unhealthy, as an ISO-8601 duration (default never);</li>
 *   <li>{@code unhealthy-during-startup-after}: how long after starting the application reports itself as unhealthy until it is ready, as an ISO-8601
 *   duration (default never);</li>
 *   <li>{@code heartbeats-during-startup}: for how long after starting the application sends watchdog heartbeats itself, every half second, bypassing
 *   {@link Systemd}, which only sends them once ready, as an ISO-8601 duration (default never);</li>
 *   <li>{@code shutdown}: how long the shutdown takes, as an ISO-8601 duration (default {@code PT0S});</li>
 *   <li>{@code on-shutdown}: what the application does when it starts shutting down: {@code stopping} to tell systemd that it is stopping, {@code close}
 *   to close the {@link Systemd} instance before it finishes shutting down, which also tells systemd that it is stopping, or {@code unhealthy} to stop the
 *   watchdog heartbeats by reporting itself as unhealthy, without telling systemd that it is stopping (default {@code stopping});</li>
 *   <li>{@code extend-timeout-on-shutdown}: {@code true} to periodically extend the shutdown timeout while shutting down, once systemd is told that the
 *   application is stopping (default {@code false});</li>
 *   <li>{@code non-ascii-status}: {@code true} to add {@value #NON_ASCII_STATUS} to the status, with characters of 2, 3 and 4 bytes in UTF-8, which is not
 *   given as an argument, as the JVM decodes its arguments in the encoding of the locale, which may not be UTF-8 (default {@code false}).</li>
 * </ul>
 *
 * <p>The status is {@code phase=starting} until the application is ready, and {@code phase=running} from then on. The watchdog heartbeats are sent when the
 * service unit sets <code>WatchdogSec</code>.
 *
 * <p>The application also reports on its standard output if the integration with systemd is usable, and why not, and when it is ready, for the tests that
 * run it without systemd.
 *
 * @author Joao Silva
 */
public final class FixtureApplication {

  private static final Logger logger = getLogger(FixtureApplication.class);

  /**
   * The status added with {@code non-ascii-status}.
   */
  public static final String NON_ASCII_STATUS = "café: 5 €, 😀";

  private static volatile String phase = "starting";

  // null until ready, or if the application never becomes unhealthy
  private static volatile Instant unhealthyAt;

  // null if the application never becomes unhealthy while starting up
  private static volatile Instant unhealthyDuringStartupAt;

  private FixtureApplication() {
  }

  /**
   * Starts the application, which then runs until stopped by systemd.
   *
   * @param args the options, each in the form {@code name=value}
   * @throws InterruptedException if interrupted while starting up
   */
  public static void main(String[] args) throws InterruptedException {
    Map<String, String> options = parse(args);
    final Duration startup = Duration.parse(options.getOrDefault("startup", "PT0S"));
    final boolean extendTimeout = Boolean.parseBoolean(options.getOrDefault("extend-timeout", "false"));
    final String unhealthyAfter = options.get("unhealthy-after");
    final Duration shutdown = Duration.parse(options.getOrDefault("shutdown", "PT0S"));
    final String onShutdown = options.getOrDefault("on-shutdown", "stopping");
    final boolean extendTimeoutOnShutdown = Boolean.parseBoolean(options.getOrDefault("extend-timeout-on-shutdown", "false"));
    final boolean nonAsciiStatus = Boolean.parseBoolean(options.getOrDefault("non-ascii-status", "false"));
    final Instant started = Instant.now();
    if (options.containsKey("unhealthy-during-startup-after")) {
      unhealthyDuringStartupAt = started.plus(Duration.parse(options.get("unhealthy-during-startup-after")));
    }
    if (options.containsKey("heartbeats-during-startup")) {
      sendHeartbeatsUntil(started.plus(Duration.parse(options.get("heartbeats-during-startup"))));
    }

    Systemd systemd = Systemd.instance();
    systemd.enableStatusUpdate(Duration.ofSeconds(1));
    systemd.enableWatchdog();
    if (extendTimeout) {
      // extends the timeout by 3 seconds every second, so that a startup timeout of a few seconds is never reached while starting up
      systemd.enableExtendTimeout(Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
    systemd.logStatus();
    logger.info("Usable: {}, load failure: {}", SystemdNotify.usable(), SystemdNotify.loadFailure().map(Throwable::getMessage).orElse("none"));
    systemd.addStatusProviders(new StatusProvider() {
      @Override
      public @NonNull String status() {
        return "phase=" + phase;
      }
    });
    if (nonAsciiStatus) {
      systemd.addStatusProviders(new StatusProvider() {
        @Override
        public @NonNull String status() {
          return NON_ASCII_STATUS;
        }
      });
    }
    systemd.addHealthProviders(() -> {
      Instant at = "starting".equals(phase) ? unhealthyDuringStartupAt : unhealthyAt;
      return at != null && Instant.now().isAfter(at) ? new Health(false, Map.of("fixture", "unhealthy on purpose")) : Health.HEALTHY;
    });
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      switch (onShutdown) {
        case "stopping" -> systemd.stopping();
        case "close" -> close(systemd);
        case "unhealthy" -> unhealthyAt = Instant.MIN;
        default -> throw new IllegalArgumentException("Unknown on-shutdown: " + onShutdown);
      }
      if (extendTimeoutOnShutdown) {
        // extends the timeout by 3 seconds every second, as while starting up
        systemd.enableExtendTimeout(Duration.ofSeconds(1), Duration.ofSeconds(3));
      }
      try {
        Thread.sleep(shutdown.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      close(systemd);
      logger.info("Stopped");
    }));

    Thread.sleep(startup.toMillis());
    if (unhealthyAfter != null) {
      unhealthyAt = Instant.now().plus(Duration.parse(unhealthyAfter));
    }
    phase = "running";
    systemd.ready();
    logger.info("Started");

    // runs until systemd stops the application, which runs the shutdown hook
    new CountDownLatch(1).await();
  }

  // sends heartbeats directly, as the library did before it only sent them once ready
  private static void sendHeartbeatsUntil(Instant until) {
    Thread heartbeats = new Thread(() -> {
      try {
        while (Instant.now().isBefore(until)) {
          SystemdNotify.watchdog();
          Thread.sleep(500);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "fixture-heartbeats");
    heartbeats.setDaemon(true);
    heartbeats.start();
  }

  private static void close(Systemd systemd) {
    try {
      systemd.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, String> parse(String... args) {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int separator = arg.indexOf('=');
      if (separator < 0) {
        throw new IllegalArgumentException("Option not in the form name=value: " + arg);
      }
      options.put(arg.substring(0, separator), arg.substring(separator + 1));
    }
    return options;
  }
}
