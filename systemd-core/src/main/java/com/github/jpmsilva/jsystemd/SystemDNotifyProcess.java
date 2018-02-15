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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;

public class SystemDNotifyProcess implements SystemDNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private int pid;

  public SystemDNotifyProcess() {
    try {
      pid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
    } catch (Throwable ignored) {
      pid = 0;
    }
  }

  @Override
  public boolean usable() {
    if (!"root".equals(System.getProperty("user.name"))) {
      logger.info(
        "The SystemD notification mode using systemd-notify is only reliable when running under root, "
          + "(see https://lists.freedesktop.org/archives/systemd-devel/2014-April/018797.html)");
      return false;
    }

    try {
      return new ProcessBuilder("systemd-notify", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.info("Could not perform a test call of systemd-notify, make sure if can be found in the process $PATH", e);
      } else {
        logger.info("Could not perform a test call of systemd-notify, make sure if can be found in the process $PATH");
      }
    }
    return false;
  }

  public void signalReady() {
    logger.info("Signaling SystemD that service is ready");
    invoke("--ready");
  }

  @Override
  public void status(String message) {
    logger.debug("Signaling SystemD that service status is {}", message);
    invoke("--status=" + message);
  }

  private void invoke(String message) {
    try {
      int exitCode;
      if (pid > 0) {
        exitCode = new ProcessBuilder("systemd-notify", message, "--pid=" + pid).start().waitFor();
      } else {
        exitCode = new ProcessBuilder("systemd-notify", message).start().waitFor();
      }
      if (exitCode != 0) {
        failed(exitCode);
      }
    } catch (IOException | InterruptedException e) {
      failed(e);
    }
  }

  private void failed(Exception e) {
    logger.warn("SystemD call failed with exception", e);
  }

  private void failed(int exitCode) {
    logger.warn("SystemD call failed with error code {}", exitCode);
  }

}
