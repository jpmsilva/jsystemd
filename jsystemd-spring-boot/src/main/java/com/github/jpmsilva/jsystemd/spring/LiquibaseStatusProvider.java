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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.visitor.AbstractChangeExecListener;
import liquibase.database.Database;
import liquibase.integration.spring.Customizer;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Implementation of {@link StatusProvider} that provides the progress of the Liquibase database migrations run on startup, such as
 * {@code Liquibase: applying changeset 042-add-index (41 applied)}.
 *
 * <p>The migrations are run by the {@link SpringLiquibase} beans, such as the one Spring Boot sets up, while they are created. This provider is a bean post
 * processor that follows their creation, and makes the {@link Liquibase} instance they run report each changeset it applies, with the customizer of the
 * bean. Each changeset also extends the startup timeout, as a migration with many changesets can take longer than the {@code TimeoutStartSec} of the service
 * unit; a single changeset that takes longer than the timeout extension still exceeds it, in which case {@code TimeoutStartSec} must be raised.
 *
 * <p>The progress is reported through a {@link PhaseStatusProvider} of its own, sent to systemd right away on every change, and cleared once the
 * migrations complete.
 * When they fail, the changeset that failed is still reported, while the application fails to start: it reports while the application starts up, and
 * while it stops, as a {@link StartingStatusProvider} and a {@link StoppingStatusProvider}, as an application that fails to start goes straight from
 * starting to stopping.
 *
 * <p>The changesets are reported with a {@link liquibase.changelog.visitor.ChangeExecListener} that is set before the customizer the bean already has, if
 * any: a customizer that sets a listener of its own replaces it, in which case the changesets are not reported.
 *
 * @author Joao Silva
 */
public class LiquibaseStatusProvider implements StartingStatusProvider, StoppingStatusProvider, BeanPostProcessor {

  private final PhaseStatusProvider phase;

  /**
   * Creates a new instance, which sends the status to systemd on every change, and extends the startup timeout on every changeset.
   *
   * <p>The created instance is not registered as a status provider of {@link Systemd#instance()}: that is up to the caller.
   */
  public LiquibaseStatusProvider() {
    this.phase = new PhaseStatusProvider(true);
  }

  /**
   * Makes the {@link Liquibase} instance run by a {@link SpringLiquibase} bean report each changeset it applies, before the bean runs the migrations.
   *
   * @param bean the bean being created
   * @param beanName the name of the bean
   * @return the bean
   */
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof SpringLiquibase liquibase) {
      Customizer<Liquibase> customizer = liquibase.getCustomizer();
      liquibase.setCustomizer(instance -> {
        instance.setChangeExecListener(new Listener());
        if (customizer != null) {
          customizer.customize(instance);
        }
      });
      phase.setPhase("Liquibase: checking the changelog");
    }
    return bean;
  }

  /**
   * Clears the progress once a {@link SpringLiquibase} bean ran the migrations.
   *
   * @param bean the bean being created
   * @param beanName the name of the bean
   * @return the bean
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof SpringLiquibase) {
      phase.clearPhase();
    }
    return bean;
  }

  @Override
  public String status() {
    return phase.status();
  }

  /**
   * Reports each changeset as it is applied.
   */
  private class Listener extends AbstractChangeExecListener {

    private final AtomicInteger applied = new AtomicInteger();

    @Override
    public void willRun(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.RunStatus runStatus) {
      phase.setPhase(String.format(Locale.ROOT, "Liquibase: applying changeset %s (%d applied)", changeSet.getId(), applied.get()));
      Systemd.instance().extendTimeout();
    }

    @Override
    public void ran(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, ChangeSet.ExecType execType) {
      applied.incrementAndGet();
    }
  }
}
