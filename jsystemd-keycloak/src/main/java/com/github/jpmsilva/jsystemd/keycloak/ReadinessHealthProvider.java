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

import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.DelayableHealthProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.keycloak.HealthReadiness.Checks;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A {@link HealthProvider} for the systemd watchdog that reports Keycloak as unhealthy while any of its readiness health checks is down, those of its
 * {@code /health/ready} endpoint, which a load balancer stops sending requests on.
 *
 * <p>The checks are run periodically on a thread of their own, once started, and the health is the outcome of their last run: they may wait for what
 * they check, such as a connection to the database, for longer than the watchdog allows between two heartbeats, and the heartbeat is only sent once
 * the health providers have answered, so running them from there could have systemd restart Keycloak however long the unhealthy state is delayed for.
 * Keycloak is also unhealthy while a run of the checks does not complete in time.
 *
 * <p>The checks mostly depend on what a restart of Keycloak does not fix, such as its database, so the unhealthy state should be delayed, with
 * {@link #setUnhealthyDelay(Duration)}, for longer than the outages Keycloak is expected to ride out.
 *
 * @author Joao Silva
 */
final class ReadinessHealthProvider extends DelayableHealthProvider {

  private final Checks checks;
  private final Duration period;
  private final Duration stuckAfter;

  // the outcome of the last run of the checks: null if they are all up, or else the names of those that are down, none if they could not run
  @Nullable
  private volatile List<String> down;

  // when the run of the checks that is under way started, as a value of nanoTime, or null between runs
  @Nullable
  private volatile Long running;

  // guarded by this
  @Nullable
  private Thread thread;

  /**
   * Creates a new instance, which reports Keycloak as healthy until started.
   *
   * @param checks the readiness health checks of Keycloak
   * @param period how long to wait between two runs of the checks
   * @param stuckAfter how long a run of the checks may take before Keycloak is unhealthy
   */
  ReadinessHealthProvider(Checks checks, Duration period, Duration stuckAfter) {
    this.checks = requireNonNull(checks, "Checks must not be null");
    this.period = requireNonNull(period, "Period must not be null");
    this.stuckAfter = requireNonNull(stuckAfter, "Stuck after must not be null");
  }

  /**
   * Starts running the checks periodically, once Keycloak is ready. Does nothing if already started.
   */
  synchronized void start() {
    if (thread == null) {
      Thread thread = new Thread(this::run, "jsystemd-readiness");
      thread.setDaemon(true);
      thread.start();
      this.thread = thread;
    }
  }

  /**
   * Stops running the checks, once Keycloak is stopping. Does nothing if not started, and the checks cannot be started again.
   */
  synchronized void stop() {
    Thread thread = this.thread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void run() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        running = System.nanoTime();
        try {
          down = checks.down();
        } catch (RuntimeException e) {
          // the checks could not run, which leaves Keycloak unhealthy until they do
          down = List.of();
        } finally {
          running = null;
        }
        Thread.sleep(period.toMillis());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Provides the health of Keycloak, without the delay: unhealthy while any readiness health check was down the last time they ran, or while they have
   * been running for too long.
   *
   * @return the health of Keycloak
   */
  @Override
  protected Health evaluateHealth() {
    Long running = this.running;
    if (running != null) {
      // values of nanoTime are only compared by their difference, as they may overflow
      Duration runningFor = Duration.ofNanos(System.nanoTime() - running);
      if (runningFor.compareTo(stuckAfter) > 0) {
        return new Health(false, Map.of("readinessHealthChecks", "running for " + runningFor.toSeconds() + " seconds"));
      }
    }
    List<String> down = this.down;
    if (down == null) {
      return Health.HEALTHY;
    }
    return new Health(false, down.isEmpty() ? Map.of("readinessHealthChecks", "could not run") : Map.of("readinessHealthChecksDown", down));
  }
}
