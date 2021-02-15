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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties class for systemd integration when using Spring Boot Actuator.
 *
 * @author Christian Lorenz
 */
@ConfigurationProperties(prefix = "systemd.health-provider")
public class SystemdHealthProviderProperties {

  /**
   * Enable integration between Spring Boot Actuator health status and systemd watchdog.
   */
  private boolean enabled;

  /**
   * Status codes from {@link Status} to consider as unhealthy.
   * If omitted {@link Status#DOWN} is used.
   */
  @NotNull
  private List<String> unhealthyStatusCodes = initStatusCodes();

  /**
   * Delay reporting unhealthy status to systemd watchdog.
   * This parameter is provided in milliseconds, and may be <code>null</code>
   * to disable the delay.
   */
  @Nullable
  private Long unhealthyPendingPeriodMs;

  private static List<String> initStatusCodes() {
    List<String> list = new ArrayList<>(1);
    list.add(Status.DOWN.getCode());
    return list;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public @NotNull List<String> getUnhealthyStatusCodes() {
    return unhealthyStatusCodes;
  }

  public void setUnhealthyStatusCodes(@NotNull List<String> unhealthyStatusCodes) {
    Objects.requireNonNull(unhealthyStatusCodes, "Unhealthy status codes must not be null");
    this.unhealthyStatusCodes = unhealthyStatusCodes;
  }

  public @Nullable Long getUnhealthyPendingPeriodMs() {
    return unhealthyPendingPeriodMs;
  }

  public void setUnhealthyPendingPeriodMs(@Nullable Long unhealthyPendingPeriodMs) {
    this.unhealthyPendingPeriodMs = unhealthyPendingPeriodMs;
  }
}
