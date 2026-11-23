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

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the progress of the bootstrap of Keycloak is reported by the factories of the other SPIs that observe it, such as
 * {@link LiquibaseProgressProviderFactory}, for the {@link SystemdProviderFactory} to show it and to extend the startup timeout: Keycloak creates each
 * factory itself, so they only know each other through here.
 *
 * <p>Reporting never fails, as it is done from within the bootstrap, which it must not break.
 *
 * @author Joao Silva
 */
final class StartupProgress {

  private static final Logger logger = LoggerFactory.getLogger(StartupProgress.class);

  // set by the factory that follows the bootstrap, while Keycloak runs
  @Nullable
  private static volatile Follower follower;

  private StartupProgress() {
  }

  /**
   * Follows the progress of the bootstrap.
   */
  @FunctionalInterface
  interface Follower {

    /**
     * Called on each step of the bootstrap.
     *
     * @param stage the stage of the startup to show, or null to keep the one shown
     * @param milestone whether the step is one that may take long, rather than one of many small ones
     */
    void progress(@Nullable String stage, boolean milestone);
  }

  /**
   * Sets who follows the progress of the bootstrap.
   *
   * @param follower the follower
   */
  static void follow(Follower follower) {
    StartupProgress.follower = follower;
  }

  /**
   * Stops following the progress of the bootstrap, if the given follower is the one that follows it, as once Keycloak stops.
   *
   * @param follower the follower that stops
   */
  static void unfollow(Follower follower) {
    if (StartupProgress.follower == follower) {
      StartupProgress.follower = null;
    }
  }

  /**
   * Reports a step of the bootstrap that may take long, such as a changeset of the database migration.
   *
   * @param stage the stage of the startup to show
   */
  static void milestone(String stage) {
    report(stage, true);
  }

  /**
   * Reports a step of the bootstrap that changes the stage of the startup, without being one that may take long, such as going back to bootstrapping
   * once a changeset is applied.
   *
   * @param stage the stage of the startup to show
   */
  static void stage(String stage) {
    report(stage, false);
  }

  /**
   * Reports one of many small steps of the bootstrap, such as each change of a changeset, which only tells that the bootstrap makes progress.
   */
  static void step() {
    report(null, false);
  }

  private static void report(@Nullable String stage, boolean milestone) {
    Follower follower = StartupProgress.follower;
    if (follower != null) {
      try {
        follower.progress(stage, milestone);
      } catch (RuntimeException e) {
        logger.debug("Could not report the progress of the bootstrap", e);
      }
    }
  }
}
