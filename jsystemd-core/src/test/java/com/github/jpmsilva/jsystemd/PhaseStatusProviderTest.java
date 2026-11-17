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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PhaseStatusProvider}.
 *
 * @author Joao Silva
 */
class PhaseStatusProviderTest {

  private final Systemd systemd = mock(Systemd.class);

  @BeforeEach
  void setUp() {
    Systemd.replaceInstance(systemd);
  }

  @AfterEach
  void tearDown() {
    Systemd.resetProcess();
  }

  @Test
  void reportsNothingWithoutPhase() {
    PhaseStatusProvider provider = new PhaseStatusProvider();

    assertThat(provider.getPhase()).isNull();
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsThePhaseAsSet() {
    PhaseStatusProvider provider = new PhaseStatusProvider();

    provider.setPhase("Reindexing 40%");

    assertThat(provider.getPhase()).isEqualTo("Reindexing 40%");
    assertThat(provider.status()).isEqualTo("Reindexing 40%");
  }

  @Test
  void reportsNothingOnceCleared() {
    PhaseStatusProvider provider = new PhaseStatusProvider();
    provider.setPhase("Reindexing 40%");

    provider.clearPhase();

    assertThat(provider.getPhase()).isNull();
    assertThat(provider.status()).isEmpty();
  }

  @Test
  void anEmptyPhaseClearsIt() {
    PhaseStatusProvider provider = new PhaseStatusProvider();
    provider.setPhase("Reindexing 40%");

    provider.setPhase("");

    assertThat(provider.getPhase()).isNull();
  }

  @Test
  void sendsEveryChangeOfPhaseRightAwayWithImmediateUpdates() {
    PhaseStatusProvider provider = new PhaseStatusProvider(true);

    provider.setPhase("Reindexing 40%");
    provider.clearPhase();

    verify(systemd, times(2)).updateStatus();
  }

  @Test
  void waitsForTheNextStatusUpdateByDefault() {
    PhaseStatusProvider provider = new PhaseStatusProvider();

    provider.setPhase("Reindexing 40%");
    provider.clearPhase();

    verify(systemd, never()).updateStatus();
  }
}
