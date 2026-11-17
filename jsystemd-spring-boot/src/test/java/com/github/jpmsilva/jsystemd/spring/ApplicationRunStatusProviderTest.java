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

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider.ApplicationState;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests for {@link ApplicationRunStatusProvider}.
 *
 * @author Joao Silva
 */
class ApplicationRunStatusProviderTest {

  private final Locale defaultLocale = Locale.getDefault();

  private MockedStatic<SystemdNotify> notify;
  private Systemd systemd;
  private ApplicationRunStatusProvider provider;

  @BeforeEach
  void setUp() {
    notify = Mockito.mockStatic(SystemdNotify.class);
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
    systemd = Systemd.instance();
    provider = new ApplicationRunStatusProvider();
  }

  @AfterEach
  void tearDown() throws Exception {
    Locale.setDefault(defaultLocale);
    systemd.close();
    notify.close();
  }

  @ParameterizedTest
  @CsvSource({
      "STARTING, starting",
      "ENVIRONMENT_PREPARED, environment prepared",
      "CONTEXT_PREPARED, context prepared",
      "CONTEXT_LOADED, context loaded",
      "STARTED, started",
      "READY, ready"
  })
  void describesStateRegardlessOfDefaultLocale(ApplicationState state, String description) {
    // under a Turkish locale, an uppercase "I" is lowercased to a dotless "ı"
    Locale.setDefault(Locale.forLanguageTag("tr"));

    provider.state(state);

    assertThat(provider.status()).isEqualTo("State: " + description);
  }

  @Test
  void reportsNothingOnceReady() {
    provider.state(ApplicationState.STARTED, Duration.ofSeconds(5));

    systemd.ready();

    assertThat(provider.status()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
      "5, 5s",
      "75, 1m15s",
      "3725, 1h02m05s"
  })
  void describesTimeTakenRegardlessOfDefaultLocale(long seconds, String timeTaken) {
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));

    provider.state(ApplicationState.STARTED, Duration.ofSeconds(seconds));

    assertThat(provider.status()).isEqualTo("State: started, time taken: " + timeTaken);
  }
}
