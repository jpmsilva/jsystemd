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

public class SystemDUtilities {

  private static final Logger logger = getLogger(lookup().lookupClass());

  private static final String notifySocket = System.getenv("NOTIFY_SOCKET");
  private static final String[] implClasses = new String[]{
      "com.github.jpmsilva.jsystemd.SystemDNotifyNative",
      "com.github.jpmsilva.jsystemd.SystemDNotifyProcess"
  };

  public static boolean hasNotifySocket() {
    return null != notifySocket;
  }

  public static String notifySocket() {
    return notifySocket;
  }

  public static SystemDNotify getSystemDNotify() {
    if (hasNotifySocket()) {
      for (String implClass : implClasses) {
        SystemDNotify impl = getImpl(implClass);
        if (impl != null) {
          return impl;
        }
      }
    }
    logger.info("Disabling SystemD notifications");
    return new SystemDNotifyDummy();
  }

  private static SystemDNotify getImpl(String implClass) {
    try {
      SystemDNotify systemDNotify = (SystemDNotify) Class.forName(implClass).newInstance();
      if (systemDNotify.usable()) {
        logger.debug("Choosing SystemD notify library {}", implClass);
        return systemDNotify;
      }
    } catch (Exception e) {
      logger.debug("Caught exception while trying to initialize {}", implClass, e);
    }
    return null;
  }

}
