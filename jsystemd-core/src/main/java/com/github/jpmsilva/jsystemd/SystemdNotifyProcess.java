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

final class SystemdNotifyProcess extends AbstractSystemdNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private int pid;

  @Override
  public boolean usable() {
    if (!"root".equals(System.getProperty("user.name"))) {
      logger.info(
          "The notification mode using systemd-notify may not be reliable without CAP_SYS_ADMIN."
              + " If the service fails to start correctly under Systemd, you may need to add \n"
              + "\tAmbientCapabilities=CAP_SYS_ADMIN\n"
              + "to the service unit.\n"
              + "See https://www.freedesktop.org/software/systemd/man/systemd-notify.html");
    }

    try {
      pid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
    } catch (Throwable ignored) {
      pid = 0;
    }

    try {
      return runCommand("systemd-notify", "--version") == 0;
    } catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.info("Could not perform a test call of systemd-notify", e);
      } else {
        logger.info("Could not perform a test call of systemd-notify");
      }
    }
    return false;
  }

  protected void invoke(String message) {
    try {
      int exitCode;
      if (pid > 0) {
        exitCode = new ProcessBuilder("systemd-notify", message, "--pid", String.valueOf(pid)).start().waitFor();
      } else {
        exitCode = new ProcessBuilder("systemd-notify", message).start().waitFor();
      }

      if (exitCode != 0) {
        failed(exitCode);
      }
    } catch (IOException e) {
      failed(e);
    } catch (InterruptedException e) {
      failed(e);
      Thread.currentThread().interrupt();
    }
  }

  private int runCommand(String... commands) throws IOException, InterruptedException {
    return new ProcessBuilder(commands).start().waitFor();
  }

  private void failed(Exception e) {
    logger.warn("Systemd call failed with exception", e);
  }

  private void failed(int exitCode) {
    logger.warn("Systemd call failed with error code {}", exitCode);
  }
}
