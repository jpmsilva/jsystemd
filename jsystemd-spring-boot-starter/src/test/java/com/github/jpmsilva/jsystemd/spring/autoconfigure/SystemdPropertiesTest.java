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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;

/**
 * Tests for {@link SystemdProperties}.
 *
 * @author Joao Silva
 */
class SystemdPropertiesTest {

  private static SystemdProperties properties(boolean detection, boolean health) {
    SystemdProperties properties = new SystemdProperties();
    properties.getStatusProviders().getThreads().setDeadlockDetection(detection);
    properties.getStatusProviders().getThreads().setDeadlockHealth(health);
    return properties;
  }

  @ParameterizedTest
  @CsvSource({
      "false, false",
      "true, false",
      "true, true"
  })
  void acceptsDeadlockHealthOnlyWithDeadlockDetection(boolean detection, boolean health) {
    assertThatCode(() -> properties(detection, health).afterPropertiesSet()).doesNotThrowAnyException();
  }

  @Test
  void rejectsDeadlockHealthWithoutDeadlockDetection() {
    assertThatThrownBy(() -> properties(false, true).afterPropertiesSet())
        .isInstanceOfSatisfying(InvalidConfigurationPropertyValueException.class, e -> {
          assertThat(e.getName()).isEqualTo("systemd.status-providers.threads.deadlock-health");
          assertThat(e.getValue()).isEqualTo(true);
          assertThat(e.getReason())
              .contains("systemd.status-providers.threads.deadlock-detection=true")
              .contains("remove 'systemd.status-providers.threads.deadlock-health'");
        });
  }

  private static SystemdProperties.WebServer webServer(SystemdProperties properties, String key) {
    return "tomcat".equals(key) ? properties.getStatusProviders().getTomcat() : properties.getStatusProviders().getJetty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"tomcat", "jetty"})
  void rejectsANegativeUnhealthyDelayOfAWebServerNamingItsProperty(String key) {
    SystemdProperties properties = new SystemdProperties();
    webServer(properties, key).setUnhealthyDelay(Duration.ofSeconds(-1));

    assertThatThrownBy(properties::afterPropertiesSet)
        .isInstanceOfSatisfying(InvalidConfigurationPropertyValueException.class,
            e -> assertThat(e.getName()).isEqualTo("systemd.status-providers." + key + ".unhealthy-delay"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"tomcat", "jetty"})
  void acceptsNoUnhealthyDelayOfAWebServer(String key) {
    SystemdProperties properties = new SystemdProperties();
    webServer(properties, key).setUnhealthyDelay(null);

    assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
  }

  private static SystemdProperties.Micrometer.Meter meter(String name) {
    SystemdProperties.Micrometer.Meter meter = new SystemdProperties.Micrometer.Meter();
    meter.setName(name);
    return meter;
  }

  @Test
  void acceptsMetersWithAName() {
    SystemdProperties properties = new SystemdProperties();
    properties.getStatusProviders().getMicrometer().getMeters().add(meter("orders.placed"));

    assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void rejectsAMeterWithoutANameNamingItsProperty(String name) {
    SystemdProperties properties = new SystemdProperties();
    properties.getStatusProviders().getMicrometer().getMeters().add(meter("orders.placed"));
    properties.getStatusProviders().getMicrometer().getMeters().add(meter(name));
    // not set at all
    properties.getStatusProviders().getMicrometer().getMeters().add(new SystemdProperties.Micrometer.Meter());

    assertThatThrownBy(properties::afterPropertiesSet)
        .isInstanceOfSatisfying(InvalidConfigurationPropertyValueException.class, e -> {
          assertThat(e.getName()).isEqualTo("systemd.status-providers.micrometer.meters[1].name");
          assertThat(e.getValue()).isEqualTo(name);
        });
  }
}
