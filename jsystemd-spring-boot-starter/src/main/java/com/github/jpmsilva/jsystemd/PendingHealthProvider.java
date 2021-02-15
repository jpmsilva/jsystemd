/*
 * Copyright 2020 TomTom N.V.
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

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Implementation of {@link HealthProvider} that suppresses/delays the unhealthy state of a delegate for a configurable period.
 *
 * @author Christian Lorenz
 */
public class PendingHealthProvider implements HealthProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  @NotNull
  private final HealthProvider delegate;
  private final long delay;
  @NotNull
  private final TemporalUnit delayUnit;
  @Nullable
  private Instant unhealthySince;

  /**
   * Creates a new health provider that delegates the actual work to the <code>delegate</code>, and delays
   * reporting unhealthy status.
   *
   * @param delegate health provider from which to obtain the true health status of the application, never <code>null</code>
   * @param delay time that has to be elapsed before the real unhealthy status is provided
   * @param delayUnit {@link TemporalUnit} for <code>delay</code>, never <code>null</code>
   */
  public PendingHealthProvider(@NotNull HealthProvider delegate, long delay, @NotNull TemporalUnit delayUnit) {
    this.delegate = Objects.requireNonNull(delegate, "Delegate must not be null");
    this.delay = delay;
    this.delayUnit = Objects.requireNonNull(delayUnit, "Delay unit must not be null");
  }

  @Override
  public Health health() {
    Health health = delegate.health();
    Instant now = Instant.now();
    if (!health.healthy) {
      if (unhealthySince == null) {
        unhealthySince = now;
      }
      Instant deadline = unhealthySince.plus(delay, delayUnit);
      logger.debug("Application unhealthy since {} (unhealthy status suppressed until {})", unhealthySince, deadline);
      if (deadline.isAfter(now)) {
        // healthy until delay has expired
        return HealthProvider.Health.healthy();
      }
    } else {
      if (unhealthySince != null) {
        logger.debug("Application healthy again (unhealthy for {}s", ChronoUnit.SECONDS.between(unhealthySince, now));
        unhealthySince = null;
      }
    }
    return health;
  }
}