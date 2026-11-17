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

import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} whose status is set by the application, to report what it is doing, such as {@code Reindexing 40%} or
 * {@code Migrating the schema}.
 *
 * <p>The status is shown as set, until it is set again or cleared. While there is no phase, nothing is reported.
 *
 * <p>When created with immediate updates, every change of phase is sent to systemd right away, rather than with the next status update.
 *
 * @author Joao Silva
 */
public class PhaseStatusProvider implements StatusProvider {

  private final boolean immediateUpdates;

  // written by the application, read by the status updates
  @Nullable
  private volatile String phase;

  /**
   * Create a new PhaseStatusProvider, whose changes of phase are sent to systemd with the next status update.
   */
  public PhaseStatusProvider() {
    this(false);
  }

  /**
   * Create a new PhaseStatusProvider, whose changes of phase are sent to systemd either right away or with the next status update.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   *
   * @param immediateUpdates <code>true</code> to send the status to systemd on every change of phase, or <code>false</code> to wait for the next status
   *     update
   */
  public PhaseStatusProvider(boolean immediateUpdates) {
    this.immediateUpdates = immediateUpdates;
  }

  /**
   * Returns the current phase.
   *
   * @return the current phase, or <code>null</code> if there is none
   */
  public @Nullable String getPhase() {
    return phase;
  }

  /**
   * Sets the current phase, which is shown as is in the status.
   *
   * @param phase the current phase, or <code>null</code> (or an empty string) to clear it
   */
  public void setPhase(@Nullable String phase) {
    this.phase = phase == null || phase.isEmpty() ? null : phase;
    if (immediateUpdates) {
      Systemd.instance().updateStatus();
    }
  }

  /**
   * Clears the current phase, so that nothing is reported.
   */
  public void clearPhase() {
    setPhase(null);
  }

  @Override
  public String status() {
    String phase = this.phase;
    return phase == null ? "" : phase;
  }
}
