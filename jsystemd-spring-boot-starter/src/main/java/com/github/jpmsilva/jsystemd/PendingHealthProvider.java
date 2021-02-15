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
import org.slf4j.Logger;

/**
 * Implementation of {@link HealthProvider} that suppresses/delays the unhealthy state of a delegate for a configurable period.
 *
 * @author Christian Lorenz
 */
public class PendingHealthProvider implements HealthProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private final HealthProvider delegate;
  private final long delay;
  private final TemporalUnit delayUnit;
  private Instant unhealthySince;

  /**
   *
   * @param delegate health provider from which to obtain the true health status of the application
   * @param delay time that has to be elapsed before the real unhealthy status is provided
   * @param delayUnit {@link TemporalUnit} for <code>delay</code>
   */
  public PendingHealthProvider(HealthProvider delegate, long delay, TemporalUnit delayUnit) {
    this.delegate = delegate;
    this.delay = delay;
    this.delayUnit = delayUnit;
  }

  @Override
  public boolean healthy() {
    boolean healthy = delegate.healthy();
    Instant now = Instant.now();
    if (!healthy) {
      if (unhealthySince == null) {
        unhealthySince = now;
      }
      Instant deadline = unhealthySince.plus(delay, delayUnit);
      logger.debug("Application unhealthy since {} (unhealthy status suppressed until {})", unhealthySince, deadline);
      if (deadline.isAfter(now)) {
        // healthy until delay has expired
        return true;
      }
    } else {
      if (unhealthySince != null) {
        logger.debug("Application healthy again (unhealthy for {}s", ChronoUnit.SECONDS.between(unhealthySince, now));
        unhealthySince = null;
      }
    }
    return healthy;
  }
}