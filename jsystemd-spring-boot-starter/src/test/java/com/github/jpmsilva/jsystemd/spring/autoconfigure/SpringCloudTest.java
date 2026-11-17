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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for the Spring Boot integration under Spring Cloud, with the legacy bootstrap context enabled: Spring Cloud then runs other applications next to the
 * actual application, which must not talk to systemd.
 *
 * @author Joao Silva
 */
class SpringCloudTest {

  private MockedStatic<?> utilities;
  private MockedStatic<SystemdNotify> notify;
  private ConfigurableApplicationContext context;

  @BeforeEach
  void setUp() {
    utilities = SystemdProcess.mockEnvironment();
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(true);
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(0L);
    notify = Mockito.mockStatic(SystemdNotify.class);

    context = new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .logStartupInfo(false)
        .properties("spring.cloud.bootstrap.enabled=true")
        .run();
  }

  @AfterEach
  void tearDown() {
    ConfigurableApplicationContext closing = context;
    while (closing != null) {
      closing.close();
      closing = (ConfigurableApplicationContext) closing.getParent();
    }
    notify.close();
    utilities.close();
  }

  @Test
  void onlyTheActualApplicationSetsUpSystemd() {
    ConfigurableApplicationContext bootstrap = (ConfigurableApplicationContext) context.getParent();

    assertThat(bootstrap).isNotNull();
    assertThat(bootstrap.getId()).isEqualTo("bootstrap");
    assertThat(bootstrap.getBeanFactory().containsLocalBean("systemdCloser")).isFalse();
    assertThat(context.getBeanFactory().containsLocalBean("systemdCloser")).isTrue();
    Systemd systemd = Systemd.instance();
    assertThat(SystemdProcess.isClosed(systemd)).isFalse();
    assertThat(systemd.getStatusProviders()).hasAtLeastOneElementOfType(ApplicationRunStatusProvider.class);
  }

  @Test
  void theShutdownIsFollowedForTheActualApplication() {
    ConfigurableApplicationContext bootstrap = (ConfigurableApplicationContext) context.getParent();
    assertThat(bootstrap).isNotNull();

    // the beans that follow the shutdown are beans of the actual application, given its context, rather than of the bootstrap one, its parent
    assertThat(context.getBeanFactory().containsLocalBean("systemdLifecycle")).isTrue();
    assertThat(context.getBeanFactory().containsLocalBean("systemdShutdownStatusProvider")).isTrue();
    assertThat(bootstrap.containsBean("systemdLifecycle")).isFalse();
    notify.clearInvocations();

    context.close();

    // only reported for the context the provider is given, which is the one closing
    notify.verify(SystemdProcess.status(() -> argThat(status -> status.startsWith("Shutdown: stopping"))));
    notify.verify(SystemdProcess.stopping());
  }

  @Test
  void refreshingTheConfigurationDoesNotBuildOtherSystemdInstances() {
    utilities.clearInvocations();

    // the run listeners of the applications that refresh the configuration, which still run their actual code
    try (MockedConstruction<SystemdSpringApplicationRunListener> listeners = Mockito.mockConstruction(SystemdSpringApplicationRunListener.class,
        Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
      // runs the applications that refresh the configuration on this thread, where the static mocks apply
      context.getBean(ContextRefresher.class).refresh();

      // the refresh did run applications through the run listener, the bootstrap one and the one Spring Cloud marks with its refresh arguments
      assertThat(listeners.constructed()).anySatisfy(listener -> Mockito.verify(listener).environmentPrepared(any(),
          argThat(environment -> environment.getPropertySources().contains("refreshArgs"))));
    }

    // a Systemd instance is only built after checking for systemd
    utilities.verify(SystemdProcess.isUnderSystemd(), never());
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({RefreshAutoConfiguration.class, SystemdAutoConfiguration.class})
  static class Application {

  }
}
