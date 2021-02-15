/*
 * Copyright 2018 Joao Silva
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

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.github.jpmsilva.jsystemd.SystemdNotifyApplicationRunStatusProvider.ApplicationState;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * A Spring Application Run Listener that sets up a {@link SystemdNotifyApplicationRunStatusProvider} to provide status updates of the current phase of the
 * application life cycle.
 *
 * @author Joao Silva
 * @see SpringApplicationRunListener
 */
public class SystemdSpringApplicationRunListener implements SpringApplicationRunListener {

  private static final String SYSTEMD_BEAN_NAME = "systemd";
  private static final boolean IS_UNDER_SYSTEMD = SystemdUtilities.isUnderSystemd();
  private static final Systemd SYSTEMD = ensureSystemd();

  private final int applicationId;
  @Nullable
  private SystemdNotifyApplicationRunStatusProvider provider;

  /**
   * Mandatory constructor of SpringApplicationRunListener.
   *
   * @param springApplication the current Spring Application
   * @param args the arguments passed to the Spring Application
   */
  @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
  public SystemdSpringApplicationRunListener(@NotNull SpringApplication springApplication, String[] args) {
    applicationId = Objects.requireNonNull(springApplication, "Spring application must not be null").hashCode();
    if (IS_UNDER_SYSTEMD) {
      provider = new SystemdNotifyApplicationRunStatusProvider(Objects.requireNonNull(SYSTEMD, "Systemd must not be null"), applicationId);
    }
  }

  private static Systemd ensureSystemd() {
    if (IS_UNDER_SYSTEMD) {
      SystemdUtilities.logSystemdStatus();
      return Systemd.builder()
          .watchdog(SystemdUtilities.watchdogUsec() / 2, MICROSECONDS)
          .statusUpdate(5, SECONDS)
          .build();
    } else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void starting() {
    if (provider != null) {
      provider.state(ApplicationState.STARTING);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void environmentPrepared(ConfigurableEnvironment environment) {
    if (provider != null) {
      provider.state(ApplicationState.ENVIRONMENT_PREPARED);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void contextPrepared(ConfigurableApplicationContext context) {
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_PREPARED);

      ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
      Systemd systemd = Objects.requireNonNull(SYSTEMD, "Systemd must not be null");
      if (!beanFactory.containsSingleton(SYSTEMD_BEAN_NAME)) {
        beanFactory.registerSingleton(SYSTEMD_BEAN_NAME, systemd);
      }
      beanFactory.registerSingleton("systemdNotifyApplicationContextStatusProvider",
          new SystemdNotifyApplicationContextStatusProvider(systemd, applicationId, context.getId(), beanFactory));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void contextLoaded(ConfigurableApplicationContext context) {
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_LOADED);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void started(ConfigurableApplicationContext context) {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void running(ConfigurableApplicationContext context) {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void failed(ConfigurableApplicationContext context, Throwable exception) {
  }
}
