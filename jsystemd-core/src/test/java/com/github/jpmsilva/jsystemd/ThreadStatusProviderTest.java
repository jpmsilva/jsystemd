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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ThreadStatusProvider}.
 *
 * @author Joao Silva
 */
class ThreadStatusProviderTest {

  @Test
  void reportsTheThreadsOfThisJvmRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      // at least this thread is running
      assertThat(new ThreadStatusProvider(false, false).status()).matches("Threads: [1-9][0-9]* \\(.*[1-9][0-9]* runnable.*\\)");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void countsTheThreadsByStateInTheOrderOfTheStates() {
    ThreadMXBean threads = threads(Thread.State.WAITING, Thread.State.RUNNABLE, Thread.State.TIMED_WAITING, Thread.State.WAITING);

    assertThat(new ThreadStatusProvider(threads, true, false).status()).isEqualTo("Threads: 4 (1 runnable; 2 waiting; 1 timed waiting)");
  }

  @Test
  void ignoresThreadsThatTerminatedWhileBeingListed() {
    ThreadMXBean threads = threads(Thread.State.RUNNABLE, null);

    assertThat(new ThreadStatusProvider(threads, true, false).status()).isEqualTo("Threads: 1 (1 runnable)");
  }

  @Test
  void reportsTheDeadlockedThreads() {
    ThreadMXBean threads = threads(Thread.State.RUNNABLE, Thread.State.BLOCKED, Thread.State.WAITING);
    when(threads.isSynchronizerUsageSupported()).thenReturn(true);
    when(threads.findDeadlockedThreads()).thenReturn(new long[] {2, 3});

    assertThat(new ThreadStatusProvider(threads, true, false).status()).isEqualTo("Threads: 3 (1 runnable; 1 blocked; 1 waiting; 2 deadlocked)");
  }

  @Test
  void findsOnlyMonitorDeadlocksWhenSynchronizerUsageIsNotSupported() {
    ThreadMXBean threads = threads(Thread.State.BLOCKED, Thread.State.BLOCKED);
    when(threads.findMonitorDeadlockedThreads()).thenReturn(new long[] {1, 2});

    assertThat(new ThreadStatusProvider(threads, true, false).status()).isEqualTo("Threads: 2 (2 blocked; 2 deadlocked)");
    verify(threads, never()).findDeadlockedThreads();
  }

  @Test
  void doesNotLookForDeadlocksWhenDisabled() {
    ThreadMXBean threads = threads(Thread.State.RUNNABLE);

    assertThat(new ThreadStatusProvider(threads, false, false).status()).isEqualTo("Threads: 1 (1 runnable)");
    verify(threads, never()).findDeadlockedThreads();
    verify(threads, never()).findMonitorDeadlockedThreads();
  }

  @Test
  void reportsNothingWithoutTheThreadMxBean() {
    assertThat(new ThreadStatusProvider(null, true, false).status()).isEmpty();
    assertThat(new ThreadStatusProvider(null, true, true).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isUnhealthyWithDeadlockedThreadsAndNamesThem() {
    ThreadMXBean threads = mock(ThreadMXBean.class);
    when(threads.isSynchronizerUsageSupported()).thenReturn(true);
    when(threads.findDeadlockedThreads()).thenReturn(new long[] {1, 2, 3});
    // the third thread terminated before its name was read
    ThreadInfo[] infos = {named("worker-1"), named("worker-2"), null};
    when(threads.getThreadInfo(new long[] {1, 2, 3})).thenReturn(infos);

    HealthProvider.Health health = new ThreadStatusProvider(threads, true, true).health();

    assertThat(health.healthy()).isFalse();
    assertThat(health.details()).containsExactly(entry("deadlockedThreads", List.of("worker-1", "worker-2")));
  }

  @Test
  void isHealthyWithoutDeadlocks() {
    ThreadMXBean threads = mock(ThreadMXBean.class);
    when(threads.findMonitorDeadlockedThreads()).thenReturn(null);

    assertThat(new ThreadStatusProvider(threads, true, true).health()).isEqualTo(HealthProvider.Health.HEALTHY);
  }

  @Test
  void isHealthyWithDeadlockedThreadsWhenDeadlockHealthIsDisabled() {
    ThreadMXBean threads = threads(Thread.State.BLOCKED, Thread.State.BLOCKED);
    when(threads.isSynchronizerUsageSupported()).thenReturn(true);
    when(threads.findDeadlockedThreads()).thenReturn(new long[] {1, 2});

    ThreadStatusProvider provider = new ThreadStatusProvider(threads, true, false);

    assertThat(provider.health()).isEqualTo(HealthProvider.Health.HEALTHY);
    verify(threads, never()).findDeadlockedThreads();
    // the deadlock is still reported in the status
    assertThat(provider.status()).isEqualTo("Threads: 2 (2 blocked; 2 deadlocked)");
  }

  @Test
  void rejectsDeadlockHealthWithoutDeadlockDetection() {
    assertThatThrownBy(() -> new ThreadStatusProvider(false, true)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires detecting deadlocks");
  }

  private static ThreadInfo named(String name) {
    ThreadInfo info = mock(ThreadInfo.class);
    when(info.getThreadName()).thenReturn(name);
    return info;
  }

  private static ThreadMXBean threads(Thread.State... states) {
    ThreadInfo[] infos = new ThreadInfo[states.length];
    long[] ids = new long[states.length];
    for (int i = 0; i < states.length; i++) {
      ids[i] = i + 1;
      if (states[i] != null) {
        infos[i] = mock(ThreadInfo.class);
        when(infos[i].getThreadState()).thenReturn(states[i]);
      }
    }
    ThreadMXBean threads = mock(ThreadMXBean.class);
    when(threads.getAllThreadIds()).thenReturn(ids);
    when(threads.getThreadInfo(any(long[].class))).thenReturn(infos);
    return threads;
  }
}
