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

import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StartingStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.StoppingStatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.core.Ordered;

/**
 * Implementation of {@link StatusProvider} that provides the progress of the Flyway database migrations run on startup, such as
 * {@code Flyway: applying migration 42 add index (41 applied)}.
 *
 * <p>The migrations are run by the {@link Flyway} instance that Spring Boot sets up, once it is created. This provider is a customizer of its
 * configuration, which adds a {@link Callback} that reports each migration it applies, next to the callbacks it already has, such as the {@link Callback}
 * beans of the application. Each migration also extends the startup timeout, as a migration of many versions can take longer than the
 * {@code TimeoutStartSec} of the service unit; a single migration that takes longer than the timeout extension still exceeds it, in which case
 * {@code TimeoutStartSec} must be raised.
 *
 * <p>The progress is reported through a {@link PhaseStatusProvider} of its own, sent to systemd right away on every change, and cleared once the
 * migrations complete.
 * When they fail, the migration that failed is still reported, while the application fails to start: it reports while the application starts up, and
 * while it stops, as a {@link StartingStatusProvider} and a {@link StoppingStatusProvider}, as an application that fails to start goes straight from
 * starting to stopping.
 *
 * <p>It is the last of the customizers to run, unless they are ordered otherwise, so that the callbacks they set are kept: a customizer that runs after
 * it and sets the callbacks replaces the one of this provider, in which case the migrations are not reported. A {@link Flyway} instance that the
 * application creates itself is not customized by Spring Boot: add the callback to its configuration with {@link #customize(FluentConfiguration)}.
 *
 * @author Joao Silva
 */
public class FlywayStatusProvider implements StartingStatusProvider, StoppingStatusProvider, FlywayConfigurationCustomizer, Ordered {

  private final PhaseStatusProvider phase;

  private final Listener listener = new Listener();

  /**
   * Creates a new instance, which sends the status to systemd on every change, and extends the startup timeout on every migration.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   */
  public FlywayStatusProvider() {
    this.phase = new PhaseStatusProvider(true);
  }

  /**
   * Makes the {@link Flyway} instance built from the given configuration report each migration it applies, keeping the callbacks the configuration
   * already has.
   *
   * @param configuration the configuration of the Flyway instance
   */
  @Override
  public void customize(FluentConfiguration configuration) {
    Callback[] callbacks = configuration.getCallbacks();
    // not added again to a configuration that is customized more than once
    if (!Arrays.asList(callbacks).contains(listener)) {
      configuration.callbacks(Stream.concat(Arrays.stream(callbacks), Stream.of(listener)).toArray(Callback[]::new));
    }
  }

  /**
   * Runs after the other customizers, so that the callbacks they set are kept.
   *
   * @return the lowest precedence
   */
  @Override
  public int getOrder() {
    return LOWEST_PRECEDENCE;
  }

  @Override
  public String status() {
    return phase.status();
  }

  /**
   * Reports each migration as it is applied.
   */
  private class Listener implements Callback {

    private final AtomicInteger applied = new AtomicInteger();

    @Override
    public boolean supports(Event event, Context context) {
      return switch (event) {
        case BEFORE_MIGRATE, BEFORE_EACH_MIGRATE, AFTER_EACH_MIGRATE, AFTER_MIGRATE -> true;
        // nothing to do when the migrations fail, for the migration that failed to be still reported
        default -> false;
      };
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
      return true;
    }

    @Override
    public void handle(Event event, Context context) {
      switch (event) {
        case BEFORE_MIGRATE -> {
          applied.set(0);
          phase.setPhase("Flyway: checking the migrations");
        }
        case BEFORE_EACH_MIGRATE -> {
          phase.setPhase(String.format(Locale.ROOT, "Flyway: applying migration %s (%d applied)", describe(context.getMigrationInfo()), applied.get()));
          Systemd.instance().extendTimeout();
        }
        case AFTER_EACH_MIGRATE -> applied.incrementAndGet();
        case AFTER_MIGRATE -> phase.clearPhase();
        default -> {
          // not supported
        }
      }
    }

    // the version and the description, as in the name of its script, such as "42 add index", or the description alone for a repeatable migration, which
    // has no version
    private static String describe(MigrationInfo migration) {
      String description = migration.getDescription();
      if (migration.getVersion() == null) {
        return description;
      }
      return description == null || description.isEmpty() ? migration.getVersion().toString() : migration.getVersion() + " " + description;
    }

    @Override
    public String getCallbackName() {
      return "jsystemd";
    }
  }
}
