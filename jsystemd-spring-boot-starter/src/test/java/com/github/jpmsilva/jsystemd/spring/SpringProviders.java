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

package com.github.jpmsilva.jsystemd.spring;

import org.jspecify.annotations.NonNull;

/**
 * Gives the tests of other packages the settings of the Spring Boot status providers.
 *
 * @author Joao Silva
 */
public final class SpringProviders {

  private SpringProviders() {
  }

  /**
   * Returns if a starved connection pool reports the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if a starved connection pool reports the application as unhealthy
   */
  public static boolean healthEnabled(@NonNull HikariStatusProvider provider) {
    return provider.healthEnabled();
  }

  /**
   * Returns if a broken liveness state reports the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if a broken liveness state reports the application as unhealthy
   */
  public static boolean healthEnabled(@NonNull AvailabilityStatusProvider provider) {
    return provider.healthEnabled();
  }
}
