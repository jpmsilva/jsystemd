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

import static java.util.concurrent.TimeUnit.SECONDS;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * An application context initializer that is responsible for creating a {@link Systemd} instance
 * and registering it in the application context under the name <code>systemd</code>. Additionally
 * it also creates an instance of {@link SystemdNotifyApplicationContextStatusProvider} and
 * registers it in the application context under the name <code>systemdNotifyApplicationContextStatusProvider</code>.
 */
public class SystemdApplicationContextInitializer implements ApplicationContextInitializer {

  private Systemd systemd;
  private SystemdNotifyApplicationContextStatusProvider provider;

  /**
   * Creates a new instance, setting up Systemd integration.
   */
  public SystemdApplicationContextInitializer() {
    if (SystemdUtilities.isUnderSystemd()) {
      systemd = Systemd.builder().statusUpdate(5, SECONDS).build();
      provider = new SystemdNotifyApplicationContextStatusProvider(systemd);
    }
  }

  /**
   * Registers a {@link Systemd} and a {@link SystemdNotifyApplicationContextStatusProvider} in the
   * application context.
   */
  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    if (SystemdUtilities.isUnderSystemd()) {
      ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
      provider.setFactory(beanFactory);
      beanFactory.registerSingleton("systemd", systemd);
      beanFactory.registerSingleton("systemdNotifyApplicationContextStatusProvider", provider);
    }
  }

  /**
   * Allows programmatic retrieval of the current {@link Systemd} integration.
   *
   * @return the current Systemd
   */
  public Systemd getSystemd() {
    return systemd;
  }

}
