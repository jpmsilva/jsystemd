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

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import org.slf4j.Logger;

abstract class AbstractSystemdNotify implements SystemdNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  @Override
  public void ready() {
    logger.info("Notifying systemd that service is ready");
    invoke("READY=1");
  }

  @Override
  public void status(String message) {
    logger.debug("Notifying systemd that service status is {}", message);
    invoke("STATUS=" + message);
  }

  @Override
  public void extendTimeout(long timeout) {
    logger.debug("Extending startup timeout with {} microseconds", timeout);
    invoke("EXTEND_TIMEOUT_USEC=" + timeout);
  }

  @Override
  public void watchdog() {
    logger.debug("Updating watchdog timestamp");
    invoke("WATCHDOG=1");
  }

  protected abstract void invoke(String message);
}
