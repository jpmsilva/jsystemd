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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.isUnderSystemd;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.notifySocketPath;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.osName;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The main systemd integration class.
 *
 * <p>Implements and exposes most of the {@code sd_notify} protocol, as well as some convenience methods.
 *
 * <p>Since this object sets up some timers for periodic communication with systemd, client code is expected to call {@link #close()} then they no longer need
 * Systemd instances. The implementation also implements {@link AutoCloseable} to ease implementation in DI containers that support the semantic.
 *
 * @author Joao Silva
 */
public class Systemd implements AutoCloseable {

  private static final Logger logger = getLogger(lookup().lookupClass());

  @NonNull
  private final AtomicLong counter = new AtomicLong(0);

  @NonNull
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, r -> {
    final Thread thread = new Thread(r);
    thread.setName(String.format("jsystemd-%d", counter.incrementAndGet()));
    return thread;
  });

  @NonNull
  private final List<SystemdStatusProvider> providers = new CopyOnWriteArrayList<>();

  @Nullable
  private HealthProvider healthProvider;

  private long period = 5;

  private TimeUnit unit = SECONDS;

  private long timeout = MICROSECONDS.convert(29, SECONDS);

  @NonNull
  private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

  @NonNull
  private final AtomicBoolean ready = new AtomicBoolean(false);

  String options = "";

  private Systemd() {
  }

  /**
   * Provides a dedicated builder instance that knows how to create Systemd instances.
   *
   * @return a builder instance
   */
  public static @NonNull Builder builder() {
    return new Builder();
  }

  /**
   * Adds the status providers to the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addStatusProviders(int index, SystemdStatusProvider... providers) {
    addStatusProviders(index, Arrays.asList(providers));
  }

  /**
   * Adds the status providers to the end of the list of providers.
   *
   * @param providers the providers to add
   */
  public void addStatusProviders(SystemdStatusProvider... providers) {
    addStatusProviders(this.providers.size(), Arrays.asList(providers));
  }

  /**
   * Adds the status providers to the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  @SuppressWarnings("WeakerAccess")
  public void addStatusProviders(int index, List<SystemdStatusProvider> providers) {
    this.providers.addAll(index, providers);
  }

  /**
   * Returns a read only view of the current providers.
   *
   * @return the current list of providers
   */
  public @NonNull List<SystemdStatusProvider> getStatusProviders() {
    return Collections.unmodifiableList(providers);
  }

  /**
   * Sets the current providers, unregistering any other previously added.
   *
   * @param providers the providers to set
   */
  public void setStatusProviders(@NonNull List<SystemdStatusProvider> providers) {
    this.providers.clear();
    this.providers.addAll(requireNonNull(providers, "Providers must not be null"));
  }

  /**
   * Returns the current health provider.
   *
   * @return the current provider
   */
  public Optional<HealthProvider> getHealthProvider() {
    return Optional.ofNullable(healthProvider);
  }

  /**
   * Sets the current health provider, overrides any other previously set.
   *
   * @param provider the provider to set, or <code>null</code> to disable the watchdog health integration
   */
  public void setHealthProvider(@Nullable HealthProvider provider) {
    this.healthProvider = provider;
  }

  private void enableStatusUpdate(long period, @NonNull TimeUnit unit) {
    executor.scheduleAtFixedRate(this::updateStatus, period, period, unit);
  }

  private void enablePeriodicExtendTimeout(long period, @NonNull TimeUnit unit, long timeout) {
    this.period = period;
    this.unit = unit;
    this.timeout = timeout;
    enablePeriodicExtendTimeout();
  }

  /**
   * Enables the periodic extension of the timeout.
   *
   * @see #extendTimeout()
   * @see #disablePeriodicExtendTimeout()
   */
  public void enablePeriodicExtendTimeout() {
    future.getAndUpdate((future) -> {
      if (future == null) {
        return executor.scheduleAtFixedRate(this::extendTimeout, period, period, unit);
      }
      return null;
    });
  }

  /**
   * Disables the periodic extension of the timeout.
   *
   * @see #extendTimeout()
   * @see #enablePeriodicExtendTimeout()
   */
  public void disablePeriodicExtendTimeout() {
    future.getAndUpdate((future) -> {
      if (future != null && !future.isDone()) {
        future.cancel(false);
      }
      return null;
    });
  }

  private void enableWatchdog(long period, @NonNull TimeUnit unit) {
    executor.scheduleAtFixedRate(this::watchdog, period, period, unit);
  }

  /**
   * Forces the current status to be calculated and sent to systemd. The method {@link Systemd.Builder#statusUpdate(long, TimeUnit)} can be used to enable
   * periodic status updates.
   */
  public void updateStatus() {
    SystemdNotify.status(providers.stream().map(SystemdStatusProvider::status).filter(t -> !t.isEmpty()).collect(Collectors.joining(", ")));
  }

  /**
   * Forces the timeout to be extended. The amount of time to extend is specified when the Systemd instance is build with
   * {@link Systemd.Builder#extendTimeout(long, TimeUnit, long)}, or 29 seconds if otherwise. Timeout extensions can only be sent during startup.
   *
   * @see Systemd.Builder#extendTimeout(long, TimeUnit, long)
   */
  public void extendTimeout() {
    if (!ready.get()) {
      SystemdNotify.extendTimeout(timeout);
    }
  }

  /**
   * Forces the watchdog timestamp to be updated. The method {@link Systemd.Builder#watchdog(long, TimeUnit)} can be used to enable periodic watchdog
   * updates.
   *
   * <p>If health provider is set and returns unhealthy the watchdog timestamp is not updated.
   */
  @SuppressWarnings("WeakerAccess")
  public void watchdog() {
    Optional<HealthProvider> healthProvider = getHealthProvider();
    if (healthProvider.isPresent() && !healthProvider.get().health().healthy) {
      logger.warn("Suppressing heartbeat to watchdog because application is unhealthy (details={})", healthProvider.get().health().details);
      return;
    }
    logger.debug("Triggering heartbeat to watchdog");
    SystemdNotify.watchdog();
  }

  /**
   * Notifies systemd that the application is ready.
   */
  public void ready() {
    if (ready.compareAndSet(false, true)) {
      SystemdNotify.ready();
      updateStatus();
    }
  }

  /**
   * Returns if the application as completed startup.
   *
   * @return {@code true} if and only if {@link #ready} has been called.
   */
  public boolean isReady() {
    return ready.get();
  }

  /**
   * Signals that the application is stopping.
   */
  public void stopping() {
    SystemdNotify.stopping();
  }

  private void options(String options) {
    this.options = options;
  }

  /**
   * Logs information regarding the status of the integration with systemd. Meant to be used once the application has done sufficient work to initialize
   * logging.
   */
  public void logStatus() {
    if (isUnderSystemd()) {
      logger.info("Running under systemd, OS name: \"{}\", notify socket: \"{}\"", osName(), notifySocketPath());
      logger.info("Enabled Systemd integration with options=({})", options);
    } else {
      logger.info("Not running under systemd, OS name: \"{}\"", osName());
    }
  }

  @Override
  public void close() throws Exception {
    synchronized (executor) {
      if (!executor.isShutdown()) {
        executor.shutdown();
        boolean terminated = executor.awaitTermination(10, SECONDS);
        if (!terminated) {
          executor.shutdownNow();
        }
      }
    }
  }

  /**
   * Specialized build class of {@link Systemd} objects.
   */
  public static class Builder {

    private Builder() {
    }

    private long statusUpdatePeriod = -1;
    private TimeUnit statusUpdateUnit;
    private long extendTimeoutPeriod = -1;
    private TimeUnit extendTimeoutUnit;
    private long extendTimeoutTimeout;
    private long watchdogPeriod = -1;
    private TimeUnit watchdogUnit;

    /**
     * Enables periodic status updates.
     *
     * @param period the period to use
     * @param unit the time unit of the period
     * @return the same builder instance
     */
    public Builder statusUpdate(long period, @NonNull TimeUnit unit) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      requireNonNull(unit, "Unit must not be null");

      this.statusUpdatePeriod = period;
      this.statusUpdateUnit = unit;
      return this;
    }

    /**
     * Enables periodic timeout extensions.
     *
     * @param period the period to use
     * @param unit the time unit of the period
     * @param timeout the amount of time to extend the timeout in microseconds
     * @return the same builder instance
     */
    @SuppressWarnings("unused")
    public Builder extendTimeout(long period, @NonNull TimeUnit unit, long timeout) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      requireNonNull(unit, "Unit must not be null");
      if (timeout <= 0) {
        throw new IllegalArgumentException("Illegal value for timeout");
      }

      this.extendTimeoutPeriod = period;
      this.extendTimeoutUnit = unit;
      this.extendTimeoutTimeout = timeout;
      return this;
    }

    /**
     * Enables periodic watchdog timestamp updates.
     *
     * @param period the period to use - must be greater than 0; if 0 this method does nothing
     * @param unit the time unit of the period
     * @return the same builder instance
     */
    public Builder watchdog(long period, @NonNull TimeUnit unit) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      requireNonNull(unit, "Unit must not be null");

      if (period > 0) {
        this.watchdogPeriod = period;
        this.watchdogUnit = unit;
      }
      return this;
    }

    /**
     * Builds a {@link Systemd} instance.
     *
     * @return the instance built
     */
    public Systemd build() {
      Systemd systemd = new Systemd();
      if (statusUpdatePeriod > -1) {
        systemd.enableStatusUpdate(statusUpdatePeriod, requireNonNull(statusUpdateUnit));
      }
      if (extendTimeoutPeriod > -1) {
        systemd.enablePeriodicExtendTimeout(extendTimeoutPeriod, requireNonNull(extendTimeoutUnit), extendTimeoutTimeout);
      }
      if (watchdogPeriod > -1) {
        systemd.enableWatchdog(watchdogPeriod, requireNonNull(watchdogUnit));
      }
      systemd.options(
          String.format("statusUpdatePeriod=%d %s, extendTimeoutPeriod=%d %s, extendTimeoutTimeout=%d MICROSECONDS, watchdogPeriod=%d %s", statusUpdatePeriod,
              statusUpdateUnit, extendTimeoutPeriod, extendTimeoutUnit, extendTimeoutTimeout, watchdogPeriod, watchdogUnit));
      return systemd;
    }
  }
}
