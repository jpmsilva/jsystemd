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

import com.github.jpmsilva.jsystemd.SystemdNotifyApplicationRunStatusProvider.ApplicationState;
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

  private SystemdNotifyApplicationRunStatusProvider provider;

  /**
   * Mandatory constructor of SpringApplicationRunListener.
   *
   * @param springApplication the current Spring Application
   * @param args the arguments passed to the Spring Application
   */
  public SystemdSpringApplicationRunListener(SpringApplication springApplication, String[] args) {
    if (SystemdUtilities.isUnderSystemd()) {
      Systemd systemd = springApplication.getInitializers().stream()
          .filter(SystemdApplicationContextInitializer.class::isInstance)
          .findFirst()
          .map(SystemdApplicationContextInitializer.class::cast)
          .map(SystemdApplicationContextInitializer::getSystemd)
          .orElseThrow(() -> new IllegalStateException("Expected systemd to be available"));
      provider = new SystemdNotifyApplicationRunStatusProvider(systemd);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void starting() {
    if (null != provider) {
      provider.state(ApplicationState.STARTING);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void environmentPrepared(ConfigurableEnvironment environment) {
    if (null != provider) {
      provider.state(ApplicationState.ENVIRONMENT_PREPARED);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void contextPrepared(ConfigurableApplicationContext context) {
    if (null != provider) {
      provider.state(ApplicationState.CONTEXT_PREPARED);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void contextLoaded(ConfigurableApplicationContext context) {
    if (null != provider) {
      provider.state(ApplicationState.CONTEXT_LOADED);
    }
  }

  /**
   * Legacy Spring Boot 1.4 method, provided here to ensure that the library works under previous versions.
   *
   * <p>Called immediately when the run method has first started. Can be used for very early initialization.
   */
  public void started() {
    if (null != provider) {
      provider.state(ApplicationState.STARTING);
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

  /**
   * Legacy Spring Boot 1.5 method, provided here to ensure that the library works under previous versions.
   *
   * <p>Called immediately before the run method finishes.
   *
   * @param context the application context or null if a failure occurred before the context was created
   * @param exception any run exception or null if run completed successfully.
   */
  @SuppressWarnings("unused")
  public void finished(ConfigurableApplicationContext context, Throwable exception) {
  }
}
