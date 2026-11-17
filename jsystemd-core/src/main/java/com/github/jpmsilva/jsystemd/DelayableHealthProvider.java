/*
 * Copyright 2020 TomTom N.V.
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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.requireNotNegative;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Base class for the health providers that support delaying their unhealthy state: while the delay has not elapsed since the application became
 * unhealthy, it is reported as healthy, so that the systemd watchdog does not restart it on short outages.
 *
 * <p>Only health providers whose unhealthy state may resolve by itself should extend this class, such as those that depend on external systems. Those
 * whose unhealthy state never resolves by itself, such as a deadlock, should implement {@link HealthProvider} directly instead, as delaying a restart
 * would only delay the recovery.
 *
 * <p>Subclasses implement {@link #evaluateHealth()}, which provides the actual health, without the delay. There is no delay until one is set with
 * {@link #setUnhealthyDelay(Duration)}. The delay is measured with {@link System#nanoTime()}, so that changes of the system clock, such as those made to
 * synchronize it, neither shorten nor lengthen it.
 *
 * @author Christian Lorenz
 * @author Joao Silva
 */
public abstract class DelayableHealthProvider implements HealthProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private final LongSupplier nanoTime;

  @Nullable
  private volatile Duration unhealthyDelay;

  // guarded by this: whether the application is unhealthy, and since when, as a value of nanoTime
  private boolean unhealthy;
  private long unhealthySince;

  /**
   * Create a new DelayableHealthProvider, without delay.
   */
  protected DelayableHealthProvider() {
    this(System::nanoTime);
  }

  DelayableHealthProvider(LongSupplier nanoTime) {
    this.nanoTime = requireNonNull(nanoTime, "Nano time must not be null");
  }

  /**
   * Returns the delay before the unhealthy state is reported.
   *
   * @return the delay, or <code>null</code> if there is no delay
   */
  public @Nullable Duration getUnhealthyDelay() {
    return unhealthyDelay;
  }

  /**
   * Sets the delay before the unhealthy state is reported.
   *
   * @param unhealthyDelay the delay, or <code>null</code> for no delay
   * @throws IllegalArgumentException if the delay is negative
   */
  public void setUnhealthyDelay(@Nullable Duration unhealthyDelay) {
    requireNotNegative(unhealthyDelay, "Unhealthy delay");
    this.unhealthyDelay = unhealthyDelay;
  }

  /**
   * Provides the health of the application, reporting it as healthy while it has been unhealthy for less than the delay.
   *
   * @return the health of the application, with the delay applied
   */
  @Override
  public final synchronized Health health() {
    Health health = evaluateHealth();
    long now = nanoTime.getAsLong();
    if (health.healthy()) {
      if (unhealthy) {
        logger.debug("Application healthy again (unhealthy for {})", Duration.ofNanos(now - unhealthySince));
        unhealthy = false;
      }
      return health;
    }
    if (!unhealthy) {
      unhealthy = true;
      unhealthySince = now;
    }
    Duration delay = unhealthyDelay;
    if (delay != null) {
      // values of nanoTime are only compared by their difference, as they may overflow
      Duration unhealthyFor = Duration.ofNanos(now - unhealthySince);
      if (unhealthyFor.compareTo(delay) < 0) {
        logger.debug("Application unhealthy for {} (unhealthy state delayed for {})", unhealthyFor, delay);
        return Health.HEALTHY;
      }
    }
    return health;
  }

  /**
   * Provides the actual health of the application, without the delay.
   *
   * @return the actual health of the application
   */
  protected abstract Health evaluateHealth();
}
