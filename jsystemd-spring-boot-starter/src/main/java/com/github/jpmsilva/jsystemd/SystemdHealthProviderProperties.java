/*
 * Copyright 2020 TomTom N.V.
 * Copyright 2023 Joao Silva
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.health.contributor.Status;

/**
 * Properties class for systemd integration when using Spring Boot Actuator.
 *
 * @author Christian Lorenz
 */
@ConfigurationProperties(prefix = "systemd.health-provider")
public class SystemdHealthProviderProperties {

  SystemdHealthProviderProperties() {
  }

  /**
   * Enable integration between Spring Boot Actuator health status and systemd watchdog.
   */
  private boolean enabled;

  /**
   * Status codes from {@link Status} to consider as unhealthy. If omitted {@link Status#DOWN} is used.
   */
  @NonNull
  private List<String> unhealthyStatusCodes = initStatusCodes();

  /**
   * Delay reporting unhealthy status to systemd watchdog. This parameter is provided in milliseconds, and may be <code>null</code> to disable the delay.
   */
  @Nullable
  private Long unhealthyPendingPeriodMs;

  private static List<String> initStatusCodes() {
    List<String> list = new ArrayList<>(1);
    list.add(Status.DOWN.getCode());
    return list;
  }

  /**
   * Check if the integration between Spring Boot Actuator health status and systemd watchdog is enabled.
   *
   * @return <code>true</code> if the integration between Spring Boot Actuator health status and systemd watchdog is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Enable integration between Spring Boot Actuator health status and systemd watchdog.
   *
   * @param enabled <code>true</code> to enable the integration between Spring Boot Actuator health status and systemd watchdog
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Check the status codes from {@link Status} to consider as unhealthy.
   *
   * @return the status codes from {@link Status} to consider as unhealthy
   * @see #setUnhealthyStatusCodes(List)
   */
  public @NonNull List<String> getUnhealthyStatusCodes() {
    return unhealthyStatusCodes;
  }

  /**
   * Set the status codes from {@link Status} to consider as unhealthy. If omitted {@link Status#DOWN} is used.
   *
   * @param unhealthyStatusCodes the status codes from {@link Status} to consider as unhealthy
   */
  public void setUnhealthyStatusCodes(@NonNull List<String> unhealthyStatusCodes) {
    Objects.requireNonNull(unhealthyStatusCodes, "Unhealthy status codes must not be null");
    this.unhealthyStatusCodes = unhealthyStatusCodes;
  }

  /**
   * Check the delay reporting unhealthy status to systemd watchdog.
   *
   * @return the delay reporting unhealthy status to systemd watchdog
   * @see #setUnhealthyPendingPeriodMs(Long)
   */
  public @Nullable Long getUnhealthyPendingPeriodMs() {
    return unhealthyPendingPeriodMs;
  }

  /**
   * Set the delay reporting unhealthy status to systemd watchdog. This parameter is provided in milliseconds, and may be <code>null</code> to disable the
   * delay.
   *
   * @param unhealthyPendingPeriodMs the delay reporting unhealthy status to systemd watchdog
   */
  public void setUnhealthyPendingPeriodMs(@Nullable Long unhealthyPendingPeriodMs) {
    this.unhealthyPendingPeriodMs = unhealthyPendingPeriodMs;
  }
}
