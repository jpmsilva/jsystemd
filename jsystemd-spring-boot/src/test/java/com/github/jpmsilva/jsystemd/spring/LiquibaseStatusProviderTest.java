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
import java.util.concurrent.atomic.AtomicBoolean;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tests for {@link LiquibaseStatusProvider}, with Liquibase migrations that really run, on an in-memory database.
 *
 * @author Joao Silva
 */
class LiquibaseStatusProviderTest {

  private final Systemd systemd = mock(Systemd.class);
  private final LiquibaseStatusProvider provider = new LiquibaseStatusProvider();
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

  private static SpringLiquibase liquibase(String changeLog) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
    liquibase.setChangeLog(changeLog);
    liquibase.setResourceLoader(new DefaultResourceLoader());
    return liquibase;
  }

  /**
   * Creates the bean, as the bean factory does: the post processor follows its initialization, which runs the migrations.
   */
  private void create(SpringLiquibase liquibase) throws LiquibaseException {
    provider.postProcessBeforeInitialization(liquibase, "liquibase");
    liquibase.afterPropertiesSet();
    provider.postProcessAfterInitialization(liquibase, "liquibase");
  }

  @Test
  void reportsEachChangesetAppliedAndClearsOnceDone() throws LiquibaseException {
    create(liquibase("classpath:db/changelog.sql"));

    assertThat(sent).containsExactly(
        "Liquibase: checking the changelog",
        "Liquibase: applying changeset create-users (0 applied)",
        "Liquibase: applying changeset create-orders (1 applied)",
        "Liquibase: applying changeset index-orders (2 applied)",
        "");
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void extendsTheStartupTimeoutForEachChangeset() throws LiquibaseException {
    create(liquibase("classpath:db/changelog.sql"));

    verify(systemd, times(3)).extendTimeout();
  }

  @Test
  void reportsNoChangesetWhenAllWereAlreadyApplied() throws LiquibaseException {
    SpringLiquibase liquibase = liquibase("classpath:db/changelog.sql");
    liquibase.afterPropertiesSet();

    create(liquibase);

    assertThat(sent).containsExactly("Liquibase: checking the changelog", "");
    verify(systemd, never()).extendTimeout();
  }

  @Test
  void keepsReportingTheChangesetThatFailed() {
    SpringLiquibase liquibase = liquibase("classpath:db/failing-changelog.sql");

    assertThatThrownBy(() -> create(liquibase)).isInstanceOf(LiquibaseException.class);

    assertThat(provider.status()).isEqualTo("Liquibase: applying changeset broken (1 applied)");
  }

  @Test
  void keepsTheCustomizerOfTheBean() throws LiquibaseException {
    SpringLiquibase liquibase = liquibase("classpath:db/changelog.sql");
    AtomicBoolean customized = new AtomicBoolean();
    liquibase.setCustomizer(instance -> customized.set(true));

    create(liquibase);

    assertThat(customized).isTrue();
    assertThat(sent).contains("Liquibase: applying changeset index-orders (2 applied)");
  }

  @Test
  void ignoresOtherBeans() {
    Object bean = new Object();

    assertThat(provider.postProcessBeforeInitialization(bean, "other")).isSameAs(bean);
    assertThat(provider.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    assertThat(sent).isEmpty();
  }
}
