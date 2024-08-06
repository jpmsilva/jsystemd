/*
 * Copyright 2018-2023 Joao Silva
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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.isUnderSystemd;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.github.jpmsilva.jsystemd.SystemdApplicationRunStatusProvider.ApplicationState;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * A Spring Application Run Listener that sets up a {@link SystemdApplicationRunStatusProvider} to provide status updates of the current phase of the
 * application life cycle.
 *
 * @author Joao Silva
 * @see SpringApplicationRunListener
 */
public class SystemdSpringApplicationRunListener implements SpringApplicationRunListener {

  private static final String SYSTEMD_BEAN_NAME = "systemd";

  private final Systemd systemd = createSystemd();

  private final int applicationId;
  @Nullable
  private SystemdApplicationRunStatusProvider provider;

  /**
   * Mandatory constructor of SpringApplicationRunListener.
   *
   * @param springApplication the current Spring Application
   * @param args the arguments passed to the Spring Application
   */
  @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
  public SystemdSpringApplicationRunListener(@NotNull SpringApplication springApplication, String[] args) {
    applicationId = requireNonNull(springApplication, "Spring application must not be null").hashCode();
    if (isUnderSystemd()) {
      provider = new SystemdApplicationRunStatusProvider(requireNonNull(systemd, "Systemd must not be null"), applicationId);
    }
  }

  private static Systemd createSystemd() {
    if (isUnderSystemd()) {
      return Systemd.builder().watchdog(SystemdUtilities.watchdogUsec() / 2, MICROSECONDS).statusUpdate(5, SECONDS).build();
    } else {
      return null;
    }
  }

  @Override
  public void starting(ConfigurableBootstrapContext bootstrapContext) {
    if (provider != null) {
      provider.state(ApplicationState.STARTING);
    }
  }

  @Override
  public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
    if (provider != null) {
      provider.state(ApplicationState.ENVIRONMENT_PREPARED);
    }
  }

  @Override
  public void contextPrepared(ConfigurableApplicationContext context) {
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_PREPARED);

      ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
      if (!beanFactory.containsSingleton(SYSTEMD_BEAN_NAME)) {
        beanFactory.registerSingleton(SYSTEMD_BEAN_NAME, requireNonNull(systemd));
      }
    beanFactory.registerSingleton("systemdApplicationContextStatusProvider",
          new SystemdApplicationContextStatusProvider(requireNonNull(systemd), applicationId, context.getId(), beanFactory));
    }
  }

  @Override
  public void contextLoaded(ConfigurableApplicationContext context) {
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_LOADED);
    }
  }

  @Override
  public void started(ConfigurableApplicationContext context, Duration timeTaken) {
    if (provider != null) {
      provider.state(ApplicationState.STARTED, timeTaken);
    }
  }

  @Override
  public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
    if (provider != null) {
      provider.state(ApplicationState.READY, timeTaken);
    }
  }
}
