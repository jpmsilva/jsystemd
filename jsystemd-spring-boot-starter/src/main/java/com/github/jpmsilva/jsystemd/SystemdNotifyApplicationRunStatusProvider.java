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

import org.springframework.core.annotation.Order;

@Order(-5000)
public class SystemdNotifyApplicationRunStatusProvider implements SystemdNotifyStatusProvider {

  private final Systemd systemd;
  private String status = "";

  SystemdNotifyApplicationRunStatusProvider(Systemd systemd) {
    this.systemd = systemd;
    this.systemd.addStatusProviders(0, this);
  }

  @Override
  public String status() {
    return systemd.isReady() ? "" : status;
  }

  void state(ApplicationState state) {
    status = String.format("State: %s", state.toString().toLowerCase().replace("_", " "));
    systemd.extendTimeout();
    systemd.updateStatus();
  }

  public enum ApplicationState {
    STARTING, ENVIRONMENT_PREPARED, CONTEXT_PREPARED, CONTEXT_LOADED
  }
}
