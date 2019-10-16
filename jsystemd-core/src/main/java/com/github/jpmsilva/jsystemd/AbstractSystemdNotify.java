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

/**
 * Base implementation that translates high level operations defined in {@link SystemdNotify} into low level {@code sd_notify} messages for sending to systemd.
 * Implementations are expected to implement {@link AbstractSystemdNotify#invoke(String)} to actually send the message to systemd.
 *
 * @author Joao Silva
 * @see <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html">sd_notify specification</a>
 */
@SuppressWarnings("unused")
abstract class AbstractSystemdNotify implements SystemdNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  /**
   * {@inheritDoc}
   */
  @Override
  public void ready() {
    logger.info("Notifying systemd that service is ready");
    invoke("READY=1");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void status(String message) {
    logger.debug("Notifying systemd that service status is {}", message);
    invoke("STATUS=" + message);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void extendTimeout(long timeout) {
    logger.debug("Extending startup timeout with {} microseconds", timeout);
    invoke("EXTEND_TIMEOUT_USEC=" + timeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void watchdog() {
    logger.debug("Updating watchdog timestamp");
    invoke("WATCHDOG=1");
  }

  /**
   * Sub classes are expected to implement this method to actually send the {@code sd_notify} formatted message to systemd.
   *
   * @param message the message to send, according to <a href="https://www.freedesktop.org/software/systemd/man/sd_notify.html#Description">specification</a>
   */
  protected abstract void invoke(String message);
}
