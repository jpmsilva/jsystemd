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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;

/**
 * Validates the durations of the properties, as the core library validates them, but so that an invalid one is reported as such, naming the property,
 * rather than as the failure to use it. Not meant to be used directly or instantiated.
 *
 * @author Joao Silva
 */
final class DurationUtilities {

  private DurationUtilities() {
  }

  /**
   * Rejects durations shorter than a microsecond, the resolution of systemd, as for the period of the status updates.
   *
   * @param property the name of the property
   * @param duration the duration
   * @throws InvalidConfigurationPropertyValueException if the duration is shorter than a microsecond
   */
  static void requireAtLeastOneMicrosecond(String property, Duration duration) {
    if (duration.compareTo(Duration.ofNanos(1000)) < 0) {
      throw new InvalidConfigurationPropertyValueException(property, duration, "The duration must be of at least a microsecond");
    }
  }

  /**
   * Rejects negative durations, as for the delays before reporting an unhealthy state. A {@code null} duration, which stands for none, is accepted.
   *
   * @param property the name of the property
   * @param duration the duration, or {@code null} for none
   * @throws InvalidConfigurationPropertyValueException if the duration is negative
   */
  static void requireNotNegative(String property, @Nullable Duration duration) {
    if (duration != null && duration.isNegative()) {
      throw new InvalidConfigurationPropertyValueException(property, duration, "The duration must not be negative");
    }
  }
}
