/*
 * Copyright 2018-2023 Joao Silva
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

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.tomcat.util.modeler.Registry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdStatusProvider} that provides information regarding Tomcat thread pools.
 *
 * @author Joao Silva
 * @see Registry
 */
@Order(1000)
public class SystemdTomcatStatusProvider implements SystemdStatusProvider {

  private static final Logger logger = getLogger(lookup().lookupClass());

  @NotNull
  private final MBeanServer mbeanServer;


  /**
   * Create a new SystemdNotifyTomcatStatusProvider.
   */
  public SystemdTomcatStatusProvider() {
    mbeanServer = Objects.requireNonNull(Registry.getRegistry(null, null).getMBeanServer(), "No usable MBeanServer instance");
  }

  @Override
  public @NotNull String status() {
    List<ConnectorStatus> statuses = new LinkedList<>();
    Set<ObjectName> objectNames;
    try {
      objectNames = mbeanServer.queryNames(new ObjectName("Tomcat:type=ThreadPool,*"), null);
    } catch (Exception e) {
      logger.warn("Could not get request processors from mBean server", e);
      return "";
    }

    for (ObjectName objectName : objectNames) {
      try {
        ConnectorStatus status = new ConnectorStatus();

        for (MBeanAttributeInfo attr : mbeanServer.getMBeanInfo(objectName).getAttributes()) {
          if (!attr.isReadable()) {
            continue;
          }

          String name = attr.getName();
          try {
            switch (name) {
              case "name" -> status.name = (String) mbeanServer.getAttribute(objectName, name);
              case "currentThreadsBusy" -> status.currentThreadsBusy = (int) mbeanServer.getAttribute(objectName, name);
              case "currentThreadCount" -> status.currentThreadCount = (int) mbeanServer.getAttribute(objectName, name);
              default -> {
              }
            }
          } catch (Throwable t) {
            logger.warn("Could not read attribute {}", name, t);
          }
        }
        statuses.add(status);
      } catch (Exception e) {
        logger.warn("Could not read object {}", objectName, e);
      }
    }
    return statuses.stream().map(ConnectorStatus::toString).collect(Collectors.joining(", "));
  }

  private static class ConnectorStatus {

    private String name;
    private int currentThreadsBusy;
    private int currentThreadCount;

    @Override
    public String toString() {
      return String.format("%s: %d/%d", name, currentThreadsBusy, currentThreadCount);
    }
  }
}
