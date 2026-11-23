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

import static org.assertj.core.api.Assertions.assertThat;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/**
 * Tests for {@link VirtualThreadStatusProvider}, with a management bean server of its own, as the scheduler of the virtual threads is only observable on
 * Java 24 and later.
 *
 * @author Joao Silva
 */
class VirtualThreadStatusProviderTest {

  private static final String SCHEDULER = "jdk.management:type=VirtualThreadScheduler";

  private final MBeanServer server = MBeanServerFactory.newMBeanServer();

  @Test
  void reportsTheVirtualThreadsOfTheScheduler() throws Exception {
    server.registerMBean(new Scheduler(8, 340, 8), new ObjectName(SCHEDULER));

    assertThat(new VirtualThreadStatusProvider(server).status()).isEqualTo("Virtual threads: 8 mounted; 340 queued; 8 carrier threads");
  }

  @Test
  void reportsNothingWithoutTheScheduler() {
    // as on the versions of Java that do not have its management bean
    assertThat(new VirtualThreadStatusProvider(server).status()).isEmpty();
  }

  @Test
  void reportsNothingOnceTheSchedulerIsGone() throws Exception {
    ObjectName name = new ObjectName(SCHEDULER);
    server.registerMBean(new Scheduler(8, 340, 8), name);
    VirtualThreadStatusProvider provider = new VirtualThreadStatusProvider(server);

    server.unregisterMBean(name);

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsNothingWhenTheSchedulerDoesNotReportItsThreads() throws Exception {
    server.registerMBean(new Other(), new ObjectName(SCHEDULER));

    assertThat(new VirtualThreadStatusProvider(server).status()).isEmpty();
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_24)
  void reportsTheVirtualThreadsOfThisJvm() {
    assertThat(new VirtualThreadStatusProvider().status()).matches("Virtual threads: [0-9]+ mounted; [0-9]+ queued; [0-9]+ carrier threads");
  }

  @Test
  @EnabledForJreRange(max = JRE.JAVA_23)
  void reportsNothingOnTheVersionsOfJavaWithoutTheScheduler() {
    assertThat(new VirtualThreadStatusProvider().status()).isEmpty();
  }

  /**
   * The attributes of the management bean of the scheduler that are read, as the JDK names them.
   */
  public interface SchedulerMBean {

    int getMountedVirtualThreadCount();

    long getQueuedVirtualThreadCount();

    int getPoolSize();
  }

  /**
   * A scheduler with the given threads.
   */
  public record Scheduler(int mounted, long queued, int poolSize) implements SchedulerMBean {

    @Override
    public int getMountedVirtualThreadCount() {
      return mounted;
    }

    @Override
    public long getQueuedVirtualThreadCount() {
      return queued;
    }

    @Override
    public int getPoolSize() {
      return poolSize;
    }
  }

  /**
   * A management bean without the attributes of the scheduler.
   */
  public interface OtherMBean {

    int getParallelism();
  }

  /**
   * A bean under the name of the scheduler, without its attributes.
   */
  public static class Other implements OtherMBean {

    @Override
    public int getParallelism() {
      return 8;
    }
  }
}
