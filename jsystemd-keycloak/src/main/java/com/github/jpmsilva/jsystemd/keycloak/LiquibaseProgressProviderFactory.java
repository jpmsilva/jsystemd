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

import com.github.jpmsilva.jsystemd.Systemd;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import liquibase.Liquibase;
import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.visitor.AbstractChangeExecListener;
import liquibase.database.Database;
import org.jspecify.annotations.Nullable;
import org.keycloak.Config;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionProvider;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the database migration that Keycloak runs while it bootstraps, such as the first time it starts, or after an upgrade, reporting each changeset
 * it applies, for the {@link SystemdProviderFactory} to show it in the status, and to extend the startup timeout.
 *
 * <p>Keycloak migrates its database with Liquibase, through the instances its Liquibase connection provider creates, which is the only place to follow
 * the migration from. This factory makes itself the default provider of that SPI, with an order above the one of Keycloak, and delegates everything to the
 * provider Keycloak would otherwise use, only adding a listener to the Liquibase instances that provider returns. Nothing else is changed, and the
 * provider of Keycloak is returned as is when not running under systemd.
 *
 * <p>That SPI is internal to Keycloak, which says so with a warning when it is built with this provider, and may change without notice, so this factory
 * is disabled unless opted into, with the build option {@code --spi-connections-liquibase--jsystemd--enabled=true}: Keycloak enables every factory unless
 * told otherwise, so this one tells it otherwise by default (see {@link #isSupported(Config.Scope)}). Once opted into, it works on a best effort basis: to
 * stay out of the way of the migration should the SPI change, the provider of Keycloak is wrapped in a dynamic proxy, rather than implemented method by
 * method, and a Liquibase instance that cannot be listened to is returned as is: the migration is then no longer followed, but still runs.
 *
 * @author Joao Silva
 */
public class LiquibaseProgressProviderFactory implements LiquibaseConnectionProviderFactory, EnvironmentDependentProviderFactory {

  /**
   * The id of the factory, which is part of its options.
   */
  public static final String ID = "jsystemd";

  // above the order of the factory of Keycloak, which is 100: the factory with the highest order is the default one of its SPI
  private static final int ORDER = 1000;

  private static final Logger logger = LoggerFactory.getLogger(LiquibaseProgressProviderFactory.class);

  // if the migration is to be followed
  private final BooleanSupplier following;

  // the changesets applied since Keycloak started, by all the Liquibase instances, as each changelog is run by its own
  private final AtomicInteger applied = new AtomicInteger();

  private final AtomicBoolean logged = new AtomicBoolean();

  // the factory of the provider of Keycloak, found on the first use
  @Nullable
  private volatile ProviderFactory<LiquibaseConnectionProvider> delegate;

  /**
   * Creates a new instance, as Keycloak does, which follows the migration when running under systemd.
   */
  public LiquibaseProgressProviderFactory() {
    this(Systemd::isUnderSystemd);
  }

  /**
   * Creates a new instance.
   *
   * @param following tells if the migration is to be followed
   */
  LiquibaseProgressProviderFactory(BooleanSupplier following) {
    this.following = following;
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public int order() {
    return ORDER;
  }

  /**
   * Tells Keycloak whether to use this factory, when it is built: only when opted into, as it implements an SPI internal to Keycloak. Keycloak only asks
   * once the {@code enabled} option, which it reads with a default of {@code true}, is not {@code false}; it is read here again with a default of
   * {@code false}, so that the factory is only used when set to {@code true}.
   *
   * @param config the options of this factory
   * @return {@code true} if opted into with {@code --spi-connections-liquibase--jsystemd--enabled=true}
   */
  @Override
  public boolean isSupported(Config.Scope config) {
    return Boolean.TRUE.equals(config.getBoolean("enabled", false));
  }

  @Override
  public void init(Config.Scope config) {
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
  }

  @Override
  public void close() {
  }

  /**
   * Creates the provider of Keycloak, which follows the migrations it runs when running under systemd.
   *
   * @param session the session of Keycloak
   * @return the provider of Keycloak, or a proxy of it
   */
  @Override
  public LiquibaseConnectionProvider create(KeycloakSession session) {
    LiquibaseConnectionProvider provider = delegate(session.getKeycloakSessionFactory()).create(session);
    return following.getAsBoolean() ? follow(provider) : provider;
  }

  // the factory Keycloak would use if this one were not there, picked as Keycloak picks the default factory of an SPI: the one with the highest order,
  // when positive, or else the one named default
  @SuppressWarnings({"rawtypes", "unchecked"})
  private ProviderFactory<LiquibaseConnectionProvider> delegate(KeycloakSessionFactory sessionFactory) {
    ProviderFactory<LiquibaseConnectionProvider> delegate = this.delegate;
    if (delegate == null) {
      List<ProviderFactory> others = sessionFactory.getProviderFactoriesStream(LiquibaseConnectionProvider.class)
          .filter(factory -> !(factory instanceof LiquibaseProgressProviderFactory)).toList();
      delegate = others.stream().filter(factory -> factory.order() > 0).max(Comparator.comparingInt(ProviderFactory::order))
          .or(() -> others.stream().filter(factory -> "default".equals(factory.getId())).findFirst())
          .or(() -> others.stream().findFirst())
          .orElseThrow(() -> new IllegalStateException("Keycloak has no Liquibase connection provider to follow"));
      this.delegate = delegate;
    }
    return delegate;
  }

  // a proxy, so that it keeps delegating whichever methods the provider has, in any version of Keycloak
  private LiquibaseConnectionProvider follow(LiquibaseConnectionProvider provider) {
    return (LiquibaseConnectionProvider) Proxy.newProxyInstance(LiquibaseConnectionProvider.class.getClassLoader(),
        new Class<?>[] {LiquibaseConnectionProvider.class}, (proxy, method, args) -> {
          Object result;
          try {
            result = method.invoke(provider, args);
          } catch (InvocationTargetException e) {
            // what the provider throws, such as a LiquibaseException
            throw e.getCause() == null ? e : e.getCause();
          }
          if (result instanceof Liquibase liquibase) {
            listen(liquibase);
          }
          return result;
        });
  }

  private void listen(Liquibase liquibase) {
    try {
      liquibase.setChangeExecListener(new Listener());
    } catch (RuntimeException | LinkageError e) {
      // as with a later version of Liquibase: the migration runs all the same
      logger.warn("Not following the database migration of Keycloak, as its Liquibase instance cannot be listened to", e);
    }
  }

  /**
   * Reports each changeset as it is applied, and each of its changes, as a changeset can make many, such as creating the tables of a release.
   */
  private class Listener extends AbstractChangeExecListener {

    @Override
    public void willRun(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.RunStatus runStatus) {
      if (logged.compareAndSet(false, true)) {
        logger.info("Following the database migration of Keycloak, as its changesets are applied");
      }
      StartupProgress.milestone(String.format(Locale.ROOT, "Startup: migrating the database (changeset %s, %d applied)", changeSet.getId(), applied.get()));
    }

    @Override
    public void willRun(Change change, ChangeSet changeSet, DatabaseChangeLog changeLog, Database database) {
      StartupProgress.step();
    }

    @Override
    public void ran(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.ExecType execType) {
      applied.incrementAndGet();
      // Liquibase does not tell when it has applied the last changeset, after which Keycloak goes on bootstrapping
      StartupProgress.stage(SystemdProviderFactory.BOOTSTRAPPING);
    }
  }
}
