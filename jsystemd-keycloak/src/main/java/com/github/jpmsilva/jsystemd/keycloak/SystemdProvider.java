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

/**
 * The provider of the systemd SPI. The integration with systemd lives in its factory, {@link SystemdProviderFactory}, which follows the life cycle of
 * Keycloak: the provider itself does nothing.
 *
 * @author Joao Silva
 */
public interface SystemdProvider extends Provider {

  @Override
  default void close() {
  }
}
