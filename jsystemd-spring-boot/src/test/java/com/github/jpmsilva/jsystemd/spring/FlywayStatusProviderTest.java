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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/**
 * Tests for {@link FlywayStatusProvider}, with Flyway migrations that really run, on an in-memory database.
 *
 * @author Joao Silva
 */
class FlywayStatusProviderTest {

  private final Systemd systemd = mock(Systemd.class);
  private final FlywayStatusProvider provider = new FlywayStatusProvider();
  // the statuses sent to systemd, in order
  private final List<String> sent = new ArrayList<>();

  @BeforeEach
  void recordSentStatuses() {
    doAnswer(invocation -> sent.add(provider.status())).when(systemd).updateStatus();
    SystemdProcess.replace(systemd);
  }

  @AfterEach
  void tearDown() {
    SystemdProcess.reset();
  }

  private static FluentConfiguration configuration(String location) {
    return Flyway.configure().dataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "").locations(location);
  }

  /**
   * Creates Flyway, as Spring Boot does: the customizers customize its configuration before it is created, and it then runs the migrations.
   */
  private void migrate(FluentConfiguration configuration) {
    provider.customize(configuration);
    configuration.load().migrate();
  }

  @Test
  void reportsEachMigrationAppliedAndClearsOnceDone() {
    migrate(configuration("classpath:db/flyway"));

    assertThat(sent).containsExactly(
        "Flyway: checking the migrations",
        "Flyway: applying migration 1 create users (0 applied)",
        "Flyway: applying migration 2 create orders (1 applied)",
        "Flyway: applying migration 3 index orders (2 applied)",
        // a repeatable migration, which has no version
        "Flyway: applying migration user orders view (3 applied)",
        "");
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void extendsTheStartupTimeoutForEachMigration() {
    migrate(configuration("classpath:db/flyway"));

    verify(systemd, times(4)).extendTimeout();
  }

  @Test
  void reportsNoMigrationWhenAllWereAlreadyApplied() {
    FluentConfiguration configuration = configuration("classpath:db/flyway");
    configuration.load().migrate();

    migrate(configuration);

    assertThat(sent).containsExactly("Flyway: checking the migrations", "");
    verify(systemd, never()).extendTimeout();
  }

  @Test
  void countsTheMigrationsOfEachRunOnItsOwn() {
    migrate(configuration("classpath:db/flyway"));
    sent.clear();

    // another database, as with several Flyway instances
    migrate(configuration("classpath:db/flyway"));

    assertThat(sent).startsWith("Flyway: checking the migrations", "Flyway: applying migration 1 create users (0 applied)");
  }

  @Test
  void keepsReportingTheMigrationThatFailed() {
    FluentConfiguration configuration = configuration("classpath:db/failing-flyway");

    assertThatThrownBy(() -> migrate(configuration)).isInstanceOf(FlywayException.class);

    assertThat(provider.status()).isEqualTo("Flyway: applying migration 2 broken (1 applied)");
  }

  @Test
  void keepsTheCallbacksOfTheConfiguration() {
    List<Event> handled = new ArrayList<>();
    Callback callback = new BaseCallback() {
      @Override
      public void handle(Event event, Context context) {
        handled.add(event);
      }
    };
    FluentConfiguration configuration = configuration("classpath:db/flyway").callbacks(callback);

    migrate(configuration);

    assertThat(configuration.getCallbacks()).hasSize(2).contains(callback);
    assertThat(handled).contains(Event.BEFORE_MIGRATE, Event.AFTER_MIGRATE);
    assertThat(sent).contains("Flyway: applying migration 3 index orders (2 applied)");
  }

  @Test
  void isOnlyAddedOnceToAConfigurationCustomizedAgain() {
    FluentConfiguration configuration = configuration("classpath:db/flyway");
    provider.customize(configuration);

    migrate(configuration);

    assertThat(configuration.getCallbacks()).hasSize(1);
    verify(systemd, times(4)).extendTimeout();
  }

  @Test
  void runsAfterTheOtherCustomizers() {
    assertThat(provider.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}
