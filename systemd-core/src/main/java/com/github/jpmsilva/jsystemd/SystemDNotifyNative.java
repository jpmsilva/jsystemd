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

import com.sun.jna.Native;
import org.slf4j.Logger;

public class SystemDNotifyNative implements SystemDNotify {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static boolean initialized = false;

  @Override
  public boolean usable() {
    return initialized;
  }

  @Override
  public void signalReady() {
    logger.info("Signaling SystemD that service is ready");
    invoke("READY=1");
  }

  @Override
  public void status(String message) {
    logger.debug("Signaling SystemD that service status is {}", message);
    invoke("STATUS=" + message);
  }

  private void invoke(String message) {
    Library.sd_notify(0, message);
  }

  @SuppressWarnings("checkstyle:ParameterName")
  private static class Library {

    static {
      try {
        Native.register("systemd");
        initialized = true;
      } catch (UnsatisfiedLinkError e) {
        if (logger.isDebugEnabled()) {
          logger.info("Could not initialize native systemd library", e);
        } else {
          logger.info("Could not initialize native systemd library");
        }
      }
    }

    public static native int sd_notify(int unset_environment, String state);

  }

}
