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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link HealthProvider} that suppresses/delays the unhealthy state of a delegate for a configurable period.
 *
 * @author Christian Lorenz
 */
public class PendingHealthProvider implements HealthProvider {

  private static final Logger LOG = LoggerFactory.getLogger(PendingHealthProvider.class);

  private final HealthProvider delegate;
  /**
   * [ms]
   */
  private final long pendingMs;
  private Instant unhealthySince;

  /**
   * @param pendingMs [ms] time that has to be elapsed before the real unhealthy status is provided
   */
  public PendingHealthProvider(HealthProvider delegate, long pendingMs) {
    this.delegate = delegate;
    this.pendingMs = pendingMs;
  }

  @Override
  public boolean healthy() {
    boolean healthy = delegate.healthy();
    if (!healthy) {
      if (unhealthySince == null) {
        unhealthySince = Instant.now();
      }
      LOG.debug("application unhealthy since {}; health status suppressed until {}", unhealthySince,
          unhealthySince.plusMillis(pendingMs));
      if (unhealthySince.plusMillis(pendingMs).isAfter(Instant.now())) {
        // healthy until delay has expired
        return true;
      }
    } else {
      if (unhealthySince != null) {
        LOG.debug("application healthy again (unhealthy for {}s",
            ChronoUnit.SECONDS.between(unhealthySince, Instant.now()));
        unhealthySince = null;
      }
    }
    return healthy;
  }
}