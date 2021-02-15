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

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(Systemd.class);

  private final SystemdNotify systemdNotify = SystemdUtilities.getSystemdNotify();
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, new BasicThreadFactory.Builder()
      .namingPattern("Systemd-%d")
      .build());

  private final List<SystemdNotifyStatusProvider> providers = new CopyOnWriteArrayList<>();
  /** can be null */
  private HealthProvider healthProvider;
  private long timeout = MICROSECONDS.convert(29, SECONDS);
  private volatile boolean ready = false;

  private Systemd() {
  }

  /**
   * Provides a dedicated builder instance that knows how to create Systemd instances.
   *
   * @return a builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Adds the status providers to the the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  public void addStatusProviders(int index, SystemdNotifyStatusProvider... providers) {
    addStatusProviders(index, Arrays.asList(providers));
  }

  /**
   * Adds the status providers to the end of the list of providers.
   *
   * @param providers the providers to add
   */
  public void addStatusProviders(SystemdNotifyStatusProvider... providers) {
    addStatusProviders(this.providers.size(), Arrays.asList(providers));
  }

  /**
   * Adds the status providers to the the list of providers in the specified position.
   *
   * @param index position to add the providers
   * @param providers the providers to add
   */
  @SuppressWarnings("WeakerAccess")
  public void addStatusProviders(int index, List<SystemdNotifyStatusProvider> providers) {
    this.providers.addAll(index, providers);
  }

  /**
   * Returns a read only view of the current providers.
   *
   * @return the current list of providers
   */
  public List<SystemdNotifyStatusProvider> getStatusProviders() {
    return Collections.unmodifiableList(providers);
  }

  /**
   * Sets the current providers, unregistering any other previously added.
   *
   * @param providers the providers to set
   */
  public void setStatusProviders(List<SystemdNotifyStatusProvider> providers) {
    this.providers.clear();
    this.providers.addAll(providers);
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
   * @param provider the provider to set; can be null
   */
  public void setHealthProvider(HealthProvider provider) {
    this.healthProvider = provider;
  }

  private void enableStatusUpdate(long period, TimeUnit unit) {
    executor.scheduleAtFixedRate(this::updateStatus, period, period, unit);
  }

  private void enableExtendTimeout(long period, TimeUnit unit, long timeout) {
    this.timeout = timeout;
    executor.scheduleAtFixedRate(this::extendTimeout, period, period, unit);
  }

  private void enableWatchdog(long period, TimeUnit unit) {
    executor.scheduleAtFixedRate(this::watchdog, period, period, unit);
  }

  /**
   * Forces the current status to be calculated and sent to systemd. The method {@link Systemd.Builder#enableStatusUpdate(long, TimeUnit)} can be used to enable
   * periodic status updates.
   */
  public void updateStatus() {
    systemdNotify.status(providers.stream()
        .map(SystemdNotifyStatusProvider::status)
        .filter(Objects::nonNull)
        .filter(t -> t.length() > 0)
        .collect(Collectors.joining(", ")));
  }

  /**
   * Forces the timeout to be extended. The amount of time to extend is specified when the Systemd instance is build with {@link
   * Systemd.Builder#enableExtendTimeout(long, TimeUnit, long)}, or 29 seconds if otherwise. Timeout extensions can only be sent during startup.
   */
  public void extendTimeout() {
    if (!ready) {
      systemdNotify.extendTimeout(timeout);
    }
  }

  /**
   * Forces the watchdog timestamp to be updated. The method {@link Systemd.Builder#enableWatchdog(long, TimeUnit)} can be used to enable periodic watchdog
   * updates.
   *
   * If health provider is set and returns unhealthy the watchdog timestamp is not updated.
   */
  @SuppressWarnings("WeakerAccess")
  public void watchdog() {
    Optional<HealthProvider> healthProvider = getHealthProvider();
    if (healthProvider.isPresent() && !healthProvider.get().healthy()) {
      LOG.warn("suppress heartbeat to watchdog because application is unhealthy");
      return;
    }
    LOG.debug("trigger heartbeat to watchdog");
    systemdNotify.watchdog();
  }

  /**
   * Notifies systemd that the unit is ready.
   */
  public void ready() {
    ready = true;
    systemdNotify.ready();
    updateStatus();
  }

  /**
   * Returns if the application as completed startup.
   *
   * @return {@code true} if and only if {@link #ready} has been called.
   */
  public boolean isReady() {
    return ready;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws Exception {
    synchronized (executor) {
      if (!executor.isShutdown()) {
        executor.shutdown();
        executor.awaitTermination(10, SECONDS);
      }
    }
  }

  /**
   * Specialized build class of {@link Systemd} objects.
   */
  public static class Builder {

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
    public Builder statusUpdate(long period, TimeUnit unit) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      if (null == unit) {
        throw new NullPointerException("Unit must not be null");
      }

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
    public Builder extendTimeout(long period, TimeUnit unit, long timeout) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      if (null == unit) {
        throw new NullPointerException("Unit must not be null");
      }
      if (timeout < 0) {
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
    public Builder watchdog(long period, TimeUnit unit) {
      if (period < 0) {
        throw new IllegalArgumentException("Illegal value for period");
      }
      if (null == unit) {
        throw new NullPointerException("Unit must not be null");
      }

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
        systemd.enableStatusUpdate(statusUpdatePeriod, statusUpdateUnit);
      }
      if (extendTimeoutPeriod > -1) {
        systemd.enableExtendTimeout(extendTimeoutPeriod, extendTimeoutUnit, extendTimeoutTimeout);
      }
      if (watchdogPeriod > -1) {
        systemd.enableWatchdog(watchdogPeriod, watchdogUnit);
      }
      LOG.info(
              "Enable Systemd integration with options=(statusUpdatePeriod={} {}, extendTimeoutPeriod={} {}, extendTimeoutTimeout={}, watchdogPeriod={} {})",
              statusUpdatePeriod, statusUpdateUnit, extendTimeoutPeriod, extendTimeoutUnit, extendTimeoutTimeout,
              watchdogPeriod, watchdogUnit);
      return systemd;
    }
  }
}
