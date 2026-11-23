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

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.mockito.MockedStatic;
import org.mockito.MockedStatic.Verification;
import org.mockito.Mockito;

/**
 * Gives the tests of other packages a process of their own, with a {@link Systemd} instance of its own, its state, and the environment and notifications
 * of systemd, to stub or verify with static mocks.
 *
 * @author Joao Silva
 */
public final class SystemdProcess {

  private SystemdProcess() {
  }

  /**
   * Forgets the instance of this process, as each test acts as if it were a process of its own.
   */
  public static void reset() {
    Systemd.resetProcess();
  }
  /**
   * Replaces the instance of this process, such as with a mock.
   *
   * @param systemd the instance to use from now on
   */
  public static void replace(@NonNull Systemd systemd) {
    Systemd.replaceInstance(systemd);
  }


  /**
   * Returns if the instance is closed.
   *
   * @param systemd the instance
   * @return {@code true} if the instance is closed
   */
  public static boolean isClosed(@NonNull Systemd systemd) {
    return systemd.isClosed();
  }

  /**
   * Returns the period of the status updates of the instance.
   *
   * @param systemd the instance
   * @return the period, or {@code null} if they are not enabled
   */
  public static @Nullable Duration statusUpdatePeriod(@NonNull Systemd systemd) {
    return systemd.statusUpdatePeriod();
  }

  /**
   * Returns the periodic watchdog heartbeats of the instance.
   *
   * @param systemd the instance
   * @return the periodic heartbeats, or {@code null} if they are not enabled
   */
  public static @Nullable Future<?> watchdogFuture(@NonNull Systemd systemd) {
    return systemd.watchdogFuture();
  }

  /**
   * Returns if the provider detects deadlocked threads.
   *
   * @param provider the provider
   * @return {@code true} if deadlocked threads are detected
   */
  public static boolean detectDeadlocks(@NonNull ThreadStatusProvider provider) {
    return provider.detectDeadlocks();
  }

  /**
   * Returns if deadlocked threads report the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if deadlocked threads report the application as unhealthy
   */
  public static boolean deadlockHealth(@NonNull ThreadStatusProvider provider) {
    return provider.deadlockHealth();
  }

  /**
   * Returns if the file descriptors about to run out report the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if the file descriptors about to run out report the application as unhealthy
   */
  public static boolean healthEnabled(@NonNull FileDescriptorStatusProvider provider) {
    return provider.healthEnabled();
  }

  /**
   * Returns the share of the limit of file descriptors from which the application is unhealthy.
   *
   * @param provider the provider
   * @return the share of the limit
   */
  public static double threshold(@NonNull FileDescriptorStatusProvider provider) {
    return provider.threshold();
  }

  /**
   * Returns if the tasks about to run out report the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if the tasks about to run out report the application as unhealthy
   */
  public static boolean healthEnabled(@NonNull CgroupTasksStatusProvider provider) {
    return provider.healthEnabled();
  }

  /**
   * Returns the share of the limit of tasks from which the application is unhealthy.
   *
   * @param provider the provider
   * @return the share of the limit
   */
  public static double threshold(@NonNull CgroupTasksStatusProvider provider) {
    return provider.threshold();
  }

  /**
   * Returns if the time taken by the garbage collection pauses reports the application as unhealthy.
   *
   * @param provider the provider
   * @return {@code true} if the time taken by the garbage collection pauses reports the application as unhealthy
   */
  public static boolean healthEnabled(@NonNull GarbageCollectionStatusProvider provider) {
    return provider.healthEnabled();
  }

  /**
   * Returns the share of the time taken by the garbage collection pauses from which the application is unhealthy.
   *
   * @param provider the provider
   * @return the share of the time
   */
  public static double threshold(@NonNull GarbageCollectionStatusProvider provider) {
    return provider.threshold();
  }

  /**
   * Mocks the environment set by systemd, calling the real methods unless stubbed with {@link #isUnderSystemd()} or {@link #watchdogUsec()}.
   *
   * @return the static mock, to close once done
   */
  public static @NonNull MockedStatic<?> mockEnvironment() {
    return Mockito.mockStatic(SystemdUtilities.class, Mockito.CALLS_REAL_METHODS);
  }

  /**
   * Checks if running under systemd, for the static mock of {@link #mockEnvironment()} to stub or verify.
   *
   * @return the check
   */
  public static @NonNull Verification isUnderSystemd() {
    return SystemdUtilities::isUnderSystemd;
  }

  /**
   * Reads <code>WATCHDOG_USEC</code>, for the static mock of {@link #mockEnvironment()} to stub or verify.
   *
   * @return the read
   */
  public static @NonNull Verification watchdogUsec() {
    return SystemdUtilities::watchdogUsec;
  }

  /**
   * Sends a status, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @param status the status, or an argument matcher of it
   * @return the notification
   */
  public static @NonNull Verification status(@NonNull Supplier<String> status) {
    return () -> SystemdNotify.status(status.get());
  }

  /**
   * Extends the timeout, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @param amount the amount, or an argument matcher of it
   * @return the notification
   */
  public static @NonNull Verification extendTimeout(@NonNull LongSupplier amount) {
    return () -> SystemdNotify.extendTimeout(amount.getAsLong());
  }

  /**
   * Tells systemd that the application is ready, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @return the notification
   */
  public static @NonNull Verification ready() {
    return SystemdNotify::ready;
  }

  /**
   * Tells systemd that the application is stopping, for a static mock of {@link SystemdNotify} to stub or verify.
   *
   * @return the notification
   */
  public static @NonNull Verification stopping() {
    return SystemdNotify::stopping;
  }

  /**
   * Returns why the native library could not be loaded, for a static mock of {@link SystemdNotify} to stub.
   *
   * @return the call
   */
  public static @NonNull Verification loadFailure() {
    return SystemdNotify::loadFailure;
  }
}
