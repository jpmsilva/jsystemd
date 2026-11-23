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

import com.github.jpmsilva.jsystemd.StatusProvider;
import org.keycloak.common.Version;

/**
 * Implementation of {@link StatusProvider} that provides the version of Keycloak that runs, such as {@code Keycloak 26.8.2}, which tells, for instance,
 * whether a node was already upgraded.
 *
 * @author Joao Silva
 */
final class VersionStatusProvider implements StatusProvider {

  @Override
  public String status() {
    return Version.NAME + " " + Version.VERSION;
  }
}
