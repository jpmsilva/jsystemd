/*
 * Copyright 2018-2026 Joao Silva
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
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.slf4j.LoggerFactory.getLogger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Generic utilities for the systemd library. Not meant to be used directly or instantiated.
 *
 * @author Joao Silva
 */
@SuppressWarnings("PMD.TooManyStaticImports")
final class SystemdUtilities {

  private static final Logger logger = getLogger(lookup().lookupClass());
  private static final @Nullable String notifySocket = System.getenv("NOTIFY_SOCKET");

  private static final @Nullable Path notifySocketPath = notifySocket != null ? Path.of(notifySocket) : null;

  private static final long watchdogUsec = readWatchdogUsec();

  private SystemdUtilities() {
  }

  private static long readWatchdogUsec() {
    String watchdogUsec = System.getenv("WATCHDOG_USEC");
    if (watchdogUsec != null && !watchdogUsec.isEmpty()) {
      try {
        return Long.parseLong(watchdogUsec);
      } catch (NumberFormatException e) {
        logger.warn("Value of environment property WATCHDOG_USEC cannot be read as a number - watchdog disabled: {}", watchdogUsec);
      }
    }
    return 0;
  }

  /**
   * Allows determining if the process is running under systemd.
   *
   * <p>A process is said to be running under systemd if <ol> <li>the operating system name starts with {@code linux};</li> <li>the environment variable
   * {@code NOTIFY_SOCKET} is set, and for a file system socket, the socket file exists (see {@link #isUsableNotifySocket(String)});</li> <li>the
   * notifications could be set up (see {@link NotifyChannel}): on Java 22 and later, with the Foreign Function and Memory API, unless native access is
   * denied, and otherwise, as on Java 17 to 21 or for {@code vsock:} addresses, the native libsystemd library could be loaded.</li> </ol>
   *
   * @return {@code true} if the process is running under systemd
   */
  static boolean isUnderSystemd() {
    return SystemdNotify.usable();
  }

  /**
   * Allows determining the current OS name.
   *
   * @return the contents of the system property {@code os.name}
   */
  static String osName() {
    return System.getProperty("os.name");
  }

  /**
   * Allows determining the current systemd notify socket.
   *
   * @return the {@link Path} corresponding the contents of the environment variable {@code NOTIFY_SOCKET}, or <code>null</code> if the environment variable is
   *     not set
   */
  static @Nullable Path notifySocketPath() {
    return notifySocketPath;
  }

  /**
   * Allows determining the current watchdog keep-alive.
   *
   * @return the contents of the environment property {@code WATCHDOG_USEC} as a long, or 0 if undefined, empty or unparseable
   */
  static long watchdogUsec() {
    return watchdogUsec;
  }

  /**
   * Allows determining the current systemd notify socket, as set.
   *
   * @return the contents of the environment variable {@code NOTIFY_SOCKET}, or <code>null</code> if the environment variable is not set
   */
  static @Nullable String notifySocket() {
    return notifySocket;
  }

  static boolean isLinux() {
    return osName().toLowerCase(Locale.ROOT).startsWith("linux");
  }

  static boolean hasNotifySocket() {
    return isUsableNotifySocket(notifySocket);
  }

  /**
   * Allows determining if a systemd notify socket address can be used.
   *
   * <p>Only file system sockets (absolute paths) can be checked for existence. Other addresses supported by systemd, such as sockets in the abstract
   * namespace (starting with {@code @}) or {@code vsock:} addresses, have no file, and are left for the channel that sends the notifications to handle.
   *
   * @param address the contents of the environment variable {@code NOTIFY_SOCKET}
   * @return {@code true} if the address is set and, for file system sockets, the socket file exists
   */
  static boolean isUsableNotifySocket(@Nullable String address) {
    if (address == null || address.isEmpty()) {
      return false;
    }
    return !address.startsWith("/") || Path.of(address).toFile().exists();
  }

  private static final String[] unitPrefixes = new String[]{"", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei"};

  static String formatByteCount(long bytes) {
    // Long.MIN_VALUE has no positive counterpart, so the largest positive value is used instead
    final long abs = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);

    int divider = 0;
    int index = 0;
    while (abs >> divider >= 1024) {
      divider += 10;
      index += 1;
    }

    final double division = abs / (double) (1L << divider) * Math.signum(bytes);
    // the root locale keeps the separators the same everywhere, such as "1,023.0 B", instead of "1.023,0 B" under a German locale
    return String.format(Locale.ROOT, "%,.1f %sB", division, unitPrefixes[index]);
  }

  /**
   * Creates an executor for periodic tasks, with a single daemon thread, so that it does not keep the JVM running. The thread is only started once a task
   * is scheduled.
   */
  static ScheduledExecutorService newExecutor(String threadName) {
    return Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, threadName);
      thread.setDaemon(true);
      return thread;
    });
  }

  /**
   * Schedules a periodic task that survives exceptions: the executor would otherwise silently suppress all subsequent executions of a task that throws.
   * The durations are truncated to whole microseconds, the resolution systemd uses for its own intervals.
   *
   * @throws IllegalArgumentException if the period is shorter than a microsecond
   */
  static ScheduledFuture<?> schedule(ScheduledExecutorService executor, String name, Runnable task,
      Duration initialDelay, Duration period) {
    return executor.scheduleAtFixedRate(() -> {
      try {
        task.run();
      } catch (Exception e) {
        logger.warn("Periodic {} failed", name, e);
      }
    }, MICROSECONDS.convert(initialDelay), MICROSECONDS.convert(period), MICROSECONDS);
  }

  /**
   * Rejects null status providers when they are registered, rather than when they are later used, far from the code that registered them.
   */
  static List<StatusProvider> requireProviders(List<StatusProvider> providers) {
    requireNonNull(providers, "Providers must not be null");
    providers.forEach(provider -> requireNonNull(provider, "Providers must not contain null elements"));
    return providers;
  }

  /**
   * Rejects null health providers when they are registered, rather than when they are later used, far from the code that registered them.
   */
  static List<HealthProvider> requireHealthProviders(List<HealthProvider> providers) {
    requireNonNull(providers, "Health providers must not be null");
    providers.forEach(provider -> requireNonNull(provider, "Health providers must not contain null elements"));
    return providers;
  }

  /**
   * Rejects durations shorter than a microsecond, the resolution of systemd, as for the periods of the periodic tasks, and the amounts to extend the
   * timeout by.
   *
   * @param duration the duration
   * @param name what the duration is, for the message of the exception, such as {@code Period}
   * @throws NullPointerException if the duration is {@code null}
   * @throws IllegalArgumentException if the duration is shorter than a microsecond
   */
  static void requireAtLeastOneMicrosecond(Duration duration, String name) {
    requireNonNull(duration, () -> name + " must not be null");
    if (MICROSECONDS.convert(duration) < 1) {
      throw new IllegalArgumentException(name + " must be of at least a microsecond, but was " + duration);
    }
  }

  /**
   * Rejects negative durations, as for the delays before reporting an unhealthy state. A {@code null} duration, which stands for none, is accepted.
   *
   * @param duration the duration, or {@code null} for none
   * @param name what the duration is, for the message of the exception, such as {@code Unhealthy delay}
   * @throws IllegalArgumentException if the duration is negative
   */
  static void requireNotNegative(@Nullable Duration duration, String name) {
    if (duration != null && duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative, but was " + duration);
    }
  }

  /**
   * Waits for an executor that is shut down to terminate, for up to 10 seconds, before interrupting its running tasks.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  static void awaitTermination(ScheduledExecutorService executor) throws InterruptedException {
    if (!executor.awaitTermination(10, SECONDS)) {
      executor.shutdownNow();
    }
  }
}
