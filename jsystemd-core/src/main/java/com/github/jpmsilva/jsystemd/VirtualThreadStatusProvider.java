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

package com.github.jpmsilva.jsystemd;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the virtual threads the JVM is running, such as
 * {@code Virtual threads: 8 mounted; 340 queued; 8 carrier threads}: the virtual threads that are running, each mounted on a platform thread of the
 * scheduler, its carrier, those that are ready to run but wait for a carrier, and the carrier threads the scheduler has.
 *
 * <p>The {@link ThreadStatusProvider} only reports platform threads, and thread pools mean little once the work runs on virtual threads, of which there
 * may be any number. What limits them is their scheduler: virtual threads that keep waiting for a carrier, while every carrier has one mounted, are held
 * back, either by more work than the CPUs can take, or by virtual threads that block without giving up their carrier, as when pinned.
 *
 * <p>Virtual threads that are blocked, waiting or sleeping are neither mounted nor queued, and are not counted: the JVM does not count them.
 *
 * <p>The scheduler is only observable on Java 24 and later: nothing is reported on earlier versions.
 *
 * @author Joao Silva
 */
public class VirtualThreadStatusProvider implements StatusProvider {

  private static final String SCHEDULER = "jdk.management:type=VirtualThreadScheduler";

  private final MBeanServer server;

  // the management bean of the scheduler of the virtual threads, or null when the JVM has none
  @Nullable
  private final ObjectName scheduler;

  /**
   * Create a new VirtualThreadStatusProvider.
   */
  public VirtualThreadStatusProvider() {
    this(ManagementFactory.getPlatformMBeanServer());
  }

  VirtualThreadStatusProvider(MBeanServer server) {
    this.server = server;
    this.scheduler = scheduler(server);
  }

  // read by name, rather than through its interface, which the minimum supported version of Java does not have
  private static @Nullable ObjectName scheduler(MBeanServer server) {
    try {
      ObjectName name = new ObjectName(SCHEDULER);
      return server.isRegistered(name) ? name : null;
    } catch (MalformedObjectNameException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String status() {
    ObjectName scheduler = this.scheduler;
    if (scheduler == null) {
      return "";
    }
    try {
      return String.format(Locale.ROOT, "Virtual threads: %d mounted; %d queued; %d carrier threads",
          ((Number) server.getAttribute(scheduler, "MountedVirtualThreadCount")).longValue(),
          ((Number) server.getAttribute(scheduler, "QueuedVirtualThreadCount")).longValue(),
          ((Number) server.getAttribute(scheduler, "PoolSize")).longValue());
    } catch (JMException | RuntimeException e) {
      // a scheduler that does not report them, such as one that is not the one of the JDK
      return "";
    }
  }
}
