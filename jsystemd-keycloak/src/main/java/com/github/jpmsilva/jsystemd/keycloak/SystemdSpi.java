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

package com.github.jpmsilva.jsystemd.keycloak;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/**
 * The systemd SPI, whose only factory, {@link SystemdProviderFactory}, integrates Keycloak with systemd. Its options are set as for any other SPI, such as
 * with {@code --spi-systemd--default--status-update-period-seconds=10}.
 *
 * @author Joao Silva
 */
public class SystemdSpi implements Spi {

  /**
   * The name of the SPI, which prefixes its options.
   */
  public static final String NAME = "systemd";

  @Override
  public boolean isInternal() {
    return false;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Class<? extends Provider> getProviderClass() {
    return SystemdProvider.class;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public Class<? extends ProviderFactory> getProviderFactoryClass() {
    return SystemdProviderFactory.class;
  }
}
