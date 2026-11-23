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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.visitor.ChangeExecListener;
import liquibase.database.Database;
import liquibase.exception.LiquibaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.connections.jpa.updater.liquibase.conn.KeycloakLiquibase;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionProvider;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderFactory;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link LiquibaseProgressProviderFactory}.
 *
 * @author Joao Silva
 */
class LiquibaseProgressProviderFactoryTest {

  private final KeycloakSession session = mock(KeycloakSession.class);
  private final KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
  private final Connection connection = mock(Connection.class);
  // the provider of Keycloak, and the Liquibase instance it runs the migration with
  private final LiquibaseConnectionProvider keycloakProvider = mock(LiquibaseConnectionProvider.class);
  private final KeycloakLiquibase liquibase = mock(KeycloakLiquibase.class);
  private final LiquibaseProgressProviderFactory factory = new LiquibaseProgressProviderFactory(() -> true);
  // the factories of the SPI, besides the one under test
  private final List<ProviderFactory<?>> factories = new ArrayList<>();
  // the steps of the bootstrap that were reported: the stage, or "step" without one, followed by "!" for those that may take long
  private final List<String> reported = new ArrayList<>();
  private final StartupProgress.Follower follower = (stage, milestone) -> reported.add((stage == null ? "step" : stage) + (milestone ? "!" : ""));

  @BeforeEach
  void setUp() throws LiquibaseException {
    when(session.getKeycloakSessionFactory()).thenReturn(sessionFactory);
    when(sessionFactory.getProviderFactoriesStream(LiquibaseConnectionProvider.class))
        .thenAnswer(invocation -> Stream.concat(Stream.of(factory), factories.stream()));
    when(keycloakProvider.getLiquibase(connection, "public")).thenReturn(liquibase);
    // as in the distribution of Keycloak, whose factory is the only one
    factories.add(keycloakFactory("quarkus", 100, keycloakProvider));
    StartupProgress.follow(follower);
  }

  @AfterEach
  void tearDown() {
    StartupProgress.unfollow(follower);
  }

  private LiquibaseConnectionProviderFactory keycloakFactory(String id, int order, LiquibaseConnectionProvider provider) {
    LiquibaseConnectionProviderFactory keycloakFactory = mock(LiquibaseConnectionProviderFactory.class);
    when(keycloakFactory.getId()).thenReturn(id);
    when(keycloakFactory.order()).thenReturn(order);
    when(keycloakFactory.create(session)).thenReturn(provider);
    return keycloakFactory;
  }

  private static ChangeSet changeSet(String id) {
    ChangeSet changeSet = mock(ChangeSet.class);
    when(changeSet.getId()).thenReturn(id);
    return changeSet;
  }

  // the listener of the Liquibase instance that the provider of Keycloak returned
  private ChangeExecListener listener() throws LiquibaseException {
    assertThat(factory.create(session).getLiquibase(connection, "public")).isSameAs(liquibase);
    ArgumentCaptor<ChangeExecListener> listener = ArgumentCaptor.forClass(ChangeExecListener.class);
    verify(liquibase).setChangeExecListener(listener.capture());
    return listener.getValue();
  }

  @Test
  void takesThePlaceOfTheFactoryOfKeycloak() {
    // Keycloak picks the factory with the highest order as the default one of its SPI, and its own has an order of 100
    assertThat(factory.order()).isGreaterThan(100);
    assertThat(factory.getId()).isEqualTo("jsystemd");
  }

  @Test
  void isRegisteredAsAFactoryOfTheSpiOfKeycloak() {
    assertThat(ServiceLoader.load(LiquibaseConnectionProviderFactory.class).stream().map(ServiceLoader.Provider::type))
        .contains(LiquibaseProgressProviderFactory.class);
  }

  @Test
  void reportsEachChangesetAsItIsApplied() throws LiquibaseException {
    ChangeExecListener listener = listener();
    DatabaseChangeLog changeLog = mock(DatabaseChangeLog.class);
    Database database = mock(Database.class);
    ChangeSet first = changeSet("1.0.0.Final-KEYCLOAK-5461");
    ChangeSet second = changeSet("26.0.0-33201");

    listener.willRun(first, changeLog, database, ChangeSet.RunStatus.NOT_RAN);
    listener.willRun(mock(Change.class), first, changeLog, database);
    listener.willRun(mock(Change.class), first, changeLog, database);
    listener.ran(first, changeLog, database, ChangeSet.ExecType.EXECUTED);
    listener.willRun(second, changeLog, database, ChangeSet.RunStatus.NOT_RAN);

    assertThat(reported).containsExactly(
        "Startup: migrating the database (changeset 1.0.0.Final-KEYCLOAK-5461, 0 applied)!",
        "step",
        "step",
        "Startup: bootstrapping",
        "Startup: migrating the database (changeset 26.0.0-33201, 1 applied)!");
  }

  @Test
  void countsTheChangesetsOfAllTheLiquibaseInstances() throws LiquibaseException {
    // each changelog is run by a Liquibase instance of its own, such as the ones of the providers that have entities of their own
    KeycloakLiquibase custom = mock(KeycloakLiquibase.class);
    when(keycloakProvider.getLiquibaseForCustomUpdate(connection, "public", "changelog.xml", getClass().getClassLoader(), "CUSTOM")).thenReturn(custom);
    ChangeExecListener listener = listener();
    listener.ran(changeSet("26.0.0-33201"), mock(DatabaseChangeLog.class), mock(Database.class), ChangeSet.ExecType.EXECUTED);

    assertThat(factory.create(session).getLiquibaseForCustomUpdate(connection, "public", "changelog.xml", getClass().getClassLoader(), "CUSTOM"))
        .isSameAs(custom);
    ArgumentCaptor<ChangeExecListener> customListener = ArgumentCaptor.forClass(ChangeExecListener.class);
    verify(custom).setChangeExecListener(customListener.capture());
    customListener.getValue().willRun(changeSet("custom-1"), mock(DatabaseChangeLog.class), mock(Database.class), ChangeSet.RunStatus.NOT_RAN);

    assertThat(reported).last().isEqualTo("Startup: migrating the database (changeset custom-1, 1 applied)!");
  }

  @Test
  void delegatesToTheFactoryWithTheHighestOrder() throws LiquibaseException {
    LiquibaseConnectionProvider other = mock(LiquibaseConnectionProvider.class);
    KeycloakLiquibase otherLiquibase = mock(KeycloakLiquibase.class);
    when(other.getLiquibase(connection, "public")).thenReturn(otherLiquibase);
    factories.add(keycloakFactory("default", 0, mock(LiquibaseConnectionProvider.class)));
    factories.add(keycloakFactory("other", 200, other));

    assertThat(factory.create(session).getLiquibase(connection, "public")).isSameAs(otherLiquibase);
  }

  @Test
  void delegatesToTheDefaultFactoryWhenNoneHasAnOrder() throws LiquibaseException {
    factories.clear();
    factories.add(keycloakFactory("other", 0, mock(LiquibaseConnectionProvider.class)));
    factories.add(keycloakFactory("default", 0, keycloakProvider));

    assertThat(factory.create(session).getLiquibase(connection, "public")).isSameAs(liquibase);
  }

  @Test
  void failsWithoutAFactoryToDelegateTo() {
    factories.clear();

    assertThatThrownBy(() -> factory.create(session)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closesTheProviderOfKeycloak() {
    factory.create(session).close();

    verify(keycloakProvider).close();
  }

  @Test
  void throwsWhatTheProviderOfKeycloakThrows() throws LiquibaseException {
    LiquibaseException failure = new LiquibaseException("no connection");
    when(keycloakProvider.getLiquibase(connection, "public")).thenThrow(failure);

    assertThatThrownBy(() -> factory.create(session).getLiquibase(connection, "public")).isSameAs(failure);
  }

  @Test
  void leavesTheMigrationAloneWhenTheLiquibaseInstanceCannotBeListenedTo() throws LiquibaseException {
    doThrow(new IllegalStateException("no listeners")).when(liquibase).setChangeExecListener(any());

    assertThat(factory.create(session).getLiquibase(connection, "public")).isSameAs(liquibase);
  }

  @Test
  void returnsTheProviderOfKeycloakAsIsWhenNotFollowing() throws LiquibaseException {
    // as when not running under systemd
    LiquibaseProgressProviderFactory factory = new LiquibaseProgressProviderFactory(() -> false);

    LiquibaseConnectionProvider provider = factory.create(session);

    assertThat(provider).isSameAs(keycloakProvider);
    assertThat(provider.getLiquibase(connection, "public")).isSameAs(liquibase);
    verify(liquibase, never()).setChangeExecListener(any());
  }

  @Test
  void neverFailsTheMigrationWhenReportingFails() throws LiquibaseException {
    StartupProgress.Follower failing = (stage, milestone) -> {
      throw new IllegalStateException("not following");
    };
    StartupProgress.follow(failing);
    try {
      ChangeExecListener listener = listener();

      listener.willRun(changeSet("26.0.0-33201"), mock(DatabaseChangeLog.class), mock(Database.class), ChangeSet.RunStatus.NOT_RAN);

      assertThat(reported).isEmpty();
    } finally {
      StartupProgress.unfollow(failing);
    }
  }
}
