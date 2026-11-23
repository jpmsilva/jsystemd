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
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Provides information regarding the live threads: as a {@link StatusProvider}, the number of threads by state, and the number of deadlocked
 * threads, if any, for the systemd status, and as a {@link HealthProvider}, for the systemd watchdog, reporting the application as unhealthy under the
 * health conditions it is configured with.
 *
 * <p>Only platform threads are reported: virtual threads are not visible through {@link ThreadMXBean}.
 *
 * <p>Each health condition is enabled on its own, and the application is healthy while none of the enabled ones holds, which is always the case when
 * none is enabled. The health conditions are:
 * <ul>
 *   <li>deadlock health: unhealthy while any thread is deadlocked, naming the deadlocked threads in the health details, under
 *   {@code deadlockedThreads}. Requires deadlock detection. As a deadlock never resolves by itself, the unhealthy state is not delayed, unlike the
 *   {@link DelayableHealthProvider} ones, and is reported as unrecoverable, which triggers the watchdog right away, rather than once
 *   {@code WatchdogSec} elapses: delaying the restart would only delay the recovery.</li>
 * </ul>
 *
 * <p>Both reading the thread states and detecting deadlocks pause the JVM briefly at a safepoint, for a time that grows with the number of threads.
 * This is negligible at the usual status update and watchdog periods.
 *
 * @author Joao Silva
 */
public class ThreadStatusProvider implements StatusProvider, HealthProvider {

  @Nullable
  private final ThreadMXBean threads;

  private final boolean detectDeadlocks;

  private final boolean deadlockHealth;

  /**
   * Create a new ThreadStatusProvider.
   *
   * @param detectDeadlocks <code>true</code> to also detect deadlocked threads, reporting them in the status
   * @param deadlockHealth <code>true</code> to report the application as unhealthy while any thread is deadlocked, which requires detecting deadlocks
   * @throws IllegalArgumentException if deadlock health is enabled without detecting deadlocks
   */
  public ThreadStatusProvider(boolean detectDeadlocks, boolean deadlockHealth) {
    this(ManagementFactory.getThreadMXBean(), detectDeadlocks, deadlockHealth);
  }

  ThreadStatusProvider(@Nullable ThreadMXBean threads, boolean detectDeadlocks, boolean deadlockHealth) {
    if (deadlockHealth && !detectDeadlocks) {
      throw new IllegalArgumentException("Deadlock health requires detecting deadlocks");
    }
    this.threads = threads;
    this.detectDeadlocks = detectDeadlocks;
    this.deadlockHealth = deadlockHealth;
  }

  // for tests: whether deadlocked threads are detected
  boolean detectDeadlocks() {
    return detectDeadlocks;
  }

  // for tests: whether deadlocked threads report the application as unhealthy
  boolean deadlockHealth() {
    return deadlockHealth;
  }

  @Override
  public String status() {
    ThreadMXBean threads = this.threads;
    if (threads == null) {
      return "";
    }
    // threads may terminate between listing them and reading their state, in which case their information is null
    Map<Thread.State, Integer> counts = new EnumMap<>(Thread.State.class);
    int total = 0;
    for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
      if (info != null) {
        counts.merge(info.getThreadState(), 1, Integer::sum);
        total++;
      }
    }

    // the statuses of all providers are joined with commas, so the details are separated otherwise
    List<String> details = new ArrayList<>();
    counts.forEach((state, count) -> details.add(String.format(Locale.ROOT, "%d %s", count, describe(state))));
    int deadlocked = detectDeadlocks ? deadlockedThreads(threads).length : 0;
    if (deadlocked > 0) {
      details.add(String.format(Locale.ROOT, "%d deadlocked", deadlocked));
    }
    return String.format(Locale.ROOT, "Threads: %d (%s)", total, String.join("; ", details));
  }

  private static String describe(Thread.State state) {
    return state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  /**
   * Reports the application as unhealthy while any of the enabled health conditions holds. Always healthy when none is enabled.
   *
   * @return the health of the application
   */
  @Override
  public Health health() {
    ThreadMXBean threads = this.threads;
    if (threads == null || !deadlockHealth) {
      return Health.HEALTHY;
    }
    long[] ids = deadlockedThreads(threads);
    if (ids.length == 0) {
      return Health.HEALTHY;
    }
    // threads may terminate between finding them and reading their names, in which case their information is null
    List<String> names = Arrays.stream(threads.getThreadInfo(ids)).filter(Objects::nonNull).map(ThreadInfo::getThreadName).toList();
    return Health.unrecoverable(Map.of("deadlockedThreads", names));
  }

  private static long [] deadlockedThreads(ThreadMXBean threads) {
    // deadlocks involving ownable synchronizers, such as ReentrantLock, can only be found when the JVM supports monitoring their usage
    long[] ids = threads.isSynchronizerUsageSupported() ? threads.findDeadlockedThreads() : threads.findMonitorDeadlockedThreads();
    // null when no thread is deadlocked
    return ids == null ? new long[0] : ids;
  }
}
