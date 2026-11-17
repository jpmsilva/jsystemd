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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ActuatorHealthStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationContextStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider;
import com.github.jpmsilva.jsystemd.spring.AvailabilityStatusProvider;
import com.github.jpmsilva.jsystemd.spring.HikariStatusProvider;
import com.github.jpmsilva.jsystemd.spring.JettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.LiquibaseStatusProvider;
import com.github.jpmsilva.jsystemd.spring.NettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ShutdownStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TaskExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TomcatStatusProvider;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.health.contributor.Status;

/**
 * Properties of the integration with systemd when using Spring Boot, all under <code>systemd</code>.
 *
 * <ul>
 *   <li><code>systemd.ready-event</code>, when systemd is told that the application is ready;</li>
 *   <li><code>systemd.status-update-period</code>, how often the status is sent to systemd;</li>
 *   <li><code>systemd.watchdog-suppress-warning</code>, whether to log the warnings about the health signalled to the systemd watchdog;</li>
 *   <li><code>systemd.status-providers.&lt;key&gt;.*</code>, each built-in {@link StatusProvider}: whether it is enabled, all of them by default
 *   except for the {@link GarbageCollectionStatusProvider} and the {@link CpuStatusProvider}, which are opted into, to keep the status short, and the
 *   other settings of those that can be configured.</li>
 * </ul>
 *
 * <p>Invalid values are rejected when the properties are bound, failing the startup of the application with an
 * {@link InvalidConfigurationPropertyValueException} that names the property.
 *
 * @author Joao Silva
 * @author Christian Lorenz
 */
@ConfigurationProperties(prefix = SystemdProperties.PREFIX)
public class SystemdProperties implements InitializingBean {

  /**
   * Prefix of the properties in this class.
   */
  static final String PREFIX = "systemd";

  /**
   * Prefix of the properties of the built-in status providers.
   */
  static final String STATUS_PROVIDERS_PREFIX = PREFIX + ".status-providers";

  /**
   * When systemd is told that the application is ready: once it accepts traffic, after the application runners and command line runners have run, or
   * once it has started, before they run, for runners that run for long, or never end.
   */
  private ReadyEvent readyEvent = ReadyEvent.ACCEPTING_TRAFFIC;

  /**
   * How often the status is sent to systemd, calling every status provider, such as the Actuator health indicators. A longer period calls the status
   * providers less often, such as health indicators that query remote services, at the cost of a less current status.
   */
  private Duration statusUpdatePeriod = Duration.ofSeconds(5);

  /**
   * Suppress the warnings logged when the application health is signalled to the systemd watchdog, but in a way that never suppresses the heartbeat,
   * such as when no health status codes are considered as unhealthy.
   */
  private boolean watchdogSuppressWarning;

  /**
   * The built-in status providers, under their keys.
   */
  private final StatusProviders statusProviders = new StatusProviders();

  SystemdProperties() {
  }

  /**
   * Returns when systemd is told that the application is ready.
   *
   * @return the event on which the application is ready
   */
  public ReadyEvent getReadyEvent() {
    return readyEvent;
  }

  /**
   * Sets when systemd is told that the application is ready.
   *
   * @param readyEvent the event on which the application is ready
   */
  public void setReadyEvent(ReadyEvent readyEvent) {
    this.readyEvent = readyEvent;
  }

  /**
   * Returns how often the status is sent to systemd.
   *
   * @return the period of the status updates
   * @see Systemd#enableStatusUpdate(Duration)
   */
  public Duration getStatusUpdatePeriod() {
    return statusUpdatePeriod;
  }

  /**
   * Sets how often the status is sent to systemd.
   *
   * @param statusUpdatePeriod the period of the status updates
   */
  public void setStatusUpdatePeriod(Duration statusUpdatePeriod) {
    this.statusUpdatePeriod = statusUpdatePeriod;
  }

  /**
   * Check if the warnings logged when the application health is signalled to the systemd watchdog, but in a way that never suppresses the heartbeat,
   * are suppressed.
   *
   * @return <code>true</code> if the warnings are suppressed
   */
  public boolean isWatchdogSuppressWarning() {
    return watchdogSuppressWarning;
  }

  /**
   * Suppress the warnings logged when the application health is signalled to the systemd watchdog, but in a way that never suppresses the heartbeat.
   *
   * @param watchdogSuppressWarning <code>true</code> to suppress the warnings
   */
  public void setWatchdogSuppressWarning(boolean watchdogSuppressWarning) {
    this.watchdogSuppressWarning = watchdogSuppressWarning;
  }

  /**
   * The settings of the built-in status providers.
   *
   * @return the settings of the status providers
   */
  public StatusProviders getStatusProviders() {
    return statusProviders;
  }

  /**
   * Rejects invalid values, naming the property, rather than failing later, or silently ignoring them.
   *
   * @throws InvalidConfigurationPropertyValueException if any value is invalid
   */
  @Override
  public void afterPropertiesSet() {
    DurationUtilities.requireAtLeastOneMicrosecond(PREFIX + ".status-update-period", statusUpdatePeriod);
    statusProviders.validate();
  }

  /**
   * The event on which systemd is told that the application is ready, with {@link Systemd#ready()}. Until then, systemd considers the service as starting
   * up, enforcing its startup timeout, and units ordered after it wait for it.
   */
  public enum ReadyEvent {

    /**
     * Once the application accepts traffic ({@link ReadinessState#ACCEPTING_TRAFFIC}), after its {@link ApplicationRunner} and
     * {@link CommandLineRunner} beans have run.
     */
    ACCEPTING_TRAFFIC,

    /**
     * Once the application has started, as soon as its application context is refreshed, before its {@link ApplicationRunner} and
     * {@link CommandLineRunner} beans run, for runners that run for long, or never end.
     */
    APPLICATION_STARTED
  }

  /**
   * The built-in status providers, under their keys: each can be enabled or disabled, and some can be configured further.
   */
  public static class StatusProviders {

    /**
     * The {@link ShutdownStatusProvider}, which reports the stage of the shutdown.
     */
    private final Toggle shutdown = new Toggle();

    /**
     * The {@link ActuatorHealthStatusProvider}, which reports the Spring Boot Actuator health status, to the systemd status and the watchdog.
     */
    private final ActuatorHealth actuatorHealth = new ActuatorHealth();

    /**
     * The {@link AvailabilityStatusProvider}, which reports the liveness and readiness of the application.
     */
    private final Availability availability = new Availability();

    /**
     * The {@link HeapStatusProvider}, which reports heap memory usage.
     */
    private final Toggle heap = new Toggle();

    /**
     * The {@link OldGenerationStatusProvider}, which reports the old generation of the heap after the last garbage collection.
     */
    private final Toggle oldGeneration = new Toggle();

    /**
     * The {@link CgroupMemoryStatusProvider}, which reports the memory used by the control group of the process.
     */
    private final Toggle cgroupMemory = new Toggle();

    /**
     * The {@link HikariStatusProvider}, which reports the HikariCP connection pools.
     */
    private final Hikari hikari = new Hikari();

    /**
     * The {@link ThreadStatusProvider}, which reports the number of threads by state, and the deadlocked threads, if any.
     */
    private final Threads threads = new Threads();

    /**
     * The {@link FileDescriptorStatusProvider}, which reports the open file descriptors.
     */
    private final FileDescriptors fileDescriptors = new FileDescriptors();

    /**
     * The {@link TomcatStatusProvider}, which reports Tomcat's connectors.
     */
    private final Toggle tomcat = new Toggle();

    /**
     * The {@link JettyStatusProvider}, which reports the connections and threads of the embedded Jetty servers.
     */
    private final Toggle jetty = new Toggle();

    /**
     * The {@link NettyStatusProvider}, which reports the connections and requests of the embedded Reactor Netty servers.
     */
    private final Toggle netty = new Toggle();

    /**
     * The {@link TaskExecutorStatusProvider}, which reports the thread pools of the task executors and schedulers.
     */
    private final Toggle taskExecutors = new Toggle();

    /**
     * The {@link CpuStatusProvider}, which reports the CPU usage. Disabled by default.
     */
    private final Toggle cpu = Toggle.disabled();

    /**
     * The {@link GarbageCollectionStatusProvider}, which reports the garbage collection pauses. Disabled by default.
     */
    private final Toggle garbageCollection = Toggle.disabled();

    /**
     * The {@link NonHeapStatusProvider}, which reports non-heap memory usage.
     */
    private final Toggle nonHeap = new Toggle();

    /**
     * The {@link BufferPoolStatusProvider}, which reports the memory used by direct and mapped buffers.
     */
    private final Toggle bufferPools = new Toggle();

    /**
     * The {@link ClassLoaderStatusProvider}, which reports loaded class count.
     */
    private final Toggle classLoader = new Toggle();

    /**
     * The {@link LiquibaseStatusProvider}, which reports the progress of the Liquibase database migrations.
     */
    private final Toggle liquibase = new Toggle();

    /**
     * The {@link ApplicationRunStatusProvider}, which reports application startup sequence state.
     */
    private final Toggle applicationRun = new Toggle();

    /**
     * The {@link ApplicationContextStatusProvider}, which reports bean creation progress.
     */
    private final Toggle applicationContext = new Toggle();

    StatusProviders() {
    }

    /**
     * The settings of the {@link ShutdownStatusProvider}.
     *
     * @return whether the {@link ShutdownStatusProvider} is enabled
     */
    public Toggle getShutdown() {
      return shutdown;
    }

    /**
     * The settings of the {@link ActuatorHealthStatusProvider}.
     *
     * @return whether the {@link ActuatorHealthStatusProvider} is enabled, and its other settings
     */
    public ActuatorHealth getActuatorHealth() {
      return actuatorHealth;
    }

    /**
     * The settings of the {@link AvailabilityStatusProvider}.
     *
     * @return whether the {@link AvailabilityStatusProvider} is enabled, and its other settings
     */
    public Availability getAvailability() {
      return availability;
    }

    /**
     * The settings of the {@link HeapStatusProvider}.
     *
     * @return whether the {@link HeapStatusProvider} is enabled
     */
    public Toggle getHeap() {
      return heap;
    }

    /**
     * The settings of the {@link OldGenerationStatusProvider}.
     *
     * @return whether the {@link OldGenerationStatusProvider} is enabled
     */
    public Toggle getOldGeneration() {
      return oldGeneration;
    }

    /**
     * The settings of the {@link CgroupMemoryStatusProvider}.
     *
     * @return whether the {@link CgroupMemoryStatusProvider} is enabled
     */
    public Toggle getCgroupMemory() {
      return cgroupMemory;
    }

    /**
     * The settings of the {@link HikariStatusProvider}.
     *
     * @return whether the {@link HikariStatusProvider} is enabled, and its other settings
     */
    public Hikari getHikari() {
      return hikari;
    }

    /**
     * The settings of the {@link ThreadStatusProvider}.
     *
     * @return whether the {@link ThreadStatusProvider} is enabled, and its other settings
     */
    public Threads getThreads() {
      return threads;
    }

    /**
     * The settings of the {@link FileDescriptorStatusProvider}.
     *
     * @return whether the {@link FileDescriptorStatusProvider} is enabled, and its other settings
     */
    public FileDescriptors getFileDescriptors() {
      return fileDescriptors;
    }

    /**
     * The settings of the {@link TomcatStatusProvider}.
     *
     * @return whether the {@link TomcatStatusProvider} is enabled
     */
    public Toggle getTomcat() {
      return tomcat;
    }

    /**
     * The settings of the {@link JettyStatusProvider}.
     *
     * @return whether the {@link JettyStatusProvider} is enabled
     */
    public Toggle getJetty() {
      return jetty;
    }

    /**
     * The settings of the {@link NettyStatusProvider}.
     *
     * @return whether the {@link NettyStatusProvider} is enabled
     */
    public Toggle getNetty() {
      return netty;
    }

    /**
     * The settings of the {@link TaskExecutorStatusProvider}.
     *
     * @return whether the {@link TaskExecutorStatusProvider} is enabled
     */
    public Toggle getTaskExecutors() {
      return taskExecutors;
    }

    /**
     * The settings of the {@link CpuStatusProvider}.
     *
     * @return whether the {@link CpuStatusProvider} is enabled
     */
    public Toggle getCpu() {
      return cpu;
    }

    /**
     * The settings of the {@link GarbageCollectionStatusProvider}.
     *
     * @return whether the {@link GarbageCollectionStatusProvider} is enabled
     */
    public Toggle getGarbageCollection() {
      return garbageCollection;
    }

    /**
     * The settings of the {@link NonHeapStatusProvider}.
     *
     * @return whether the {@link NonHeapStatusProvider} is enabled
     */
    public Toggle getNonHeap() {
      return nonHeap;
    }

    /**
     * The settings of the {@link BufferPoolStatusProvider}.
     *
     * @return whether the {@link BufferPoolStatusProvider} is enabled
     */
    public Toggle getBufferPools() {
      return bufferPools;
    }

    /**
     * The settings of the {@link ClassLoaderStatusProvider}.
     *
     * @return whether the {@link ClassLoaderStatusProvider} is enabled
     */
    public Toggle getClassLoader() {
      return classLoader;
    }

    /**
     * The settings of the {@link LiquibaseStatusProvider}.
     *
     * @return whether the {@link LiquibaseStatusProvider} is enabled
     */
    public Toggle getLiquibase() {
      return liquibase;
    }

    /**
     * The settings of the {@link ApplicationRunStatusProvider}.
     *
     * @return whether the {@link ApplicationRunStatusProvider} is enabled
     */
    public Toggle getApplicationRun() {
      return applicationRun;
    }

    /**
     * The settings of the {@link ApplicationContextStatusProvider}.
     *
     * @return whether the {@link ApplicationContextStatusProvider} is enabled
     */
    public Toggle getApplicationContext() {
      return applicationContext;
    }

    private void validate() {
      threads.validate();
      fileDescriptors.validate();
      availability.validate();
      hikari.validate();
      actuatorHealth.validate();
    }
  }

  /**
   * Enables or disables one status provider.
   */
  public static class Toggle {

    /**
     * Enable the status provider.
     */
    // true, as most status providers are enabled by default: the configuration metadata reads the default from here, as long as no constructor sets it,
    // and the status providers that are disabled by default have their own default in the additional configuration metadata
    private boolean enabled = true;

    Toggle() {
    }

    private static Toggle disabled() {
      Toggle toggle = new Toggle();
      toggle.setEnabled(false);
      return toggle;
    }

    /**
     * Check if the status provider is enabled.
     *
     * @return <code>true</code> if the status provider is enabled
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Enable or disable the status provider.
     *
     * @param enabled <code>true</code> to enable the status provider
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /**
   * The {@link ThreadStatusProvider}, which provides information regarding the live threads, and detects deadlocks.
   *
   * <p>The {@link ThreadStatusProvider} is always a health provider of the watchdog, while enabled, and these properties control its health conditions.
   * Each health condition is opted into on its own, as it lets systemd restart the application: without any, the application is always healthy.
   *
   * <p>Both deadlock detection and deadlock health are disabled by default: detecting deadlocks shows them in the systemd status, and deadlock health
   * makes the application unhealthy while they last. Deadlock health requires detecting deadlocks: enabling <code>deadlock-health</code> while
   * <code>deadlock-detection</code> is disabled is an error, which fails the startup of the application.
   */
  public static class Threads extends Toggle {

    private static final String PREFIX = STATUS_PROVIDERS_PREFIX + ".threads";

    /**
     * Detect deadlocked threads, and report them in the systemd status.
     */
    private boolean deadlockDetection;

    /**
     * Suppress the watchdog heartbeat while any thread is deadlocked, so that systemd restarts the application. Requires deadlock detection: enabling it
     * while deadlock detection is disabled is an error.
     */
    private boolean deadlockHealth;

    Threads() {
    }

    /**
     * Check if deadlocked threads are detected.
     *
     * @return <code>true</code> if deadlocked threads are detected
     */
    public boolean isDeadlockDetection() {
      return deadlockDetection;
    }

    /**
     * Enable or disable the detection of deadlocked threads.
     *
     * @param deadlockDetection <code>true</code> to detect deadlocked threads
     */
    public void setDeadlockDetection(boolean deadlockDetection) {
      this.deadlockDetection = deadlockDetection;
    }

    /**
     * Check if the watchdog heartbeat is suppressed while any thread is deadlocked.
     *
     * @return <code>true</code> if deadlocked threads make the application unhealthy
     */
    public boolean isDeadlockHealth() {
      return deadlockHealth;
    }

    /**
     * Enable or disable suppressing the watchdog heartbeat while any thread is deadlocked. Requires deadlock detection.
     *
     * @param deadlockHealth <code>true</code> to make the application unhealthy while any thread is deadlocked
     */
    public void setDeadlockHealth(boolean deadlockHealth) {
      this.deadlockHealth = deadlockHealth;
    }

    // rejects signalling deadlocks without detecting them, which would otherwise silently never signal anything
    void validate() {
      if (deadlockHealth && !deadlockDetection) {
        throw new InvalidConfigurationPropertyValueException(PREFIX + ".deadlock-health", true,
            "Signalling deadlocks requires detecting them: set '" + PREFIX + ".deadlock-detection=true', or remove '" + PREFIX + ".deadlock-health'");
      }
    }
  }

  /**
   * The {@link FileDescriptorStatusProvider}, which provides information regarding the open file descriptors.
   *
   * <p>The {@link FileDescriptorStatusProvider} is always a health provider of the watchdog, while enabled, and these properties control its health: it
   * is opted into, as it lets systemd restart the application, so the application is always healthy unless enabled.
   */
  public static class FileDescriptors extends Toggle {

    private static final String PREFIX = STATUS_PROVIDERS_PREFIX + ".file-descriptors";

    /**
     * Suppress the watchdog heartbeat while the open file descriptors are at or above the threshold, so that systemd restarts the application.
     */
    private boolean health;

    /**
     * Share of the limit of file descriptors from which the application is unhealthy, greater than 0 and at most 1, such as <code>0.9</code> for 90%.
     * Only applies with health enabled.
     */
    private double threshold = 0.9;

    /**
     * Delay suppressing the watchdog heartbeat until the open file descriptors have been at or above the threshold for this long, such as
     * <code>30s</code>, so that a short burst of connections does not restart the application. Values without a unit are in milliseconds. Not set by
     * default, which disables the delay. Only applies with health enabled.
     */
    @Nullable
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration unhealthyDelay;

    FileDescriptors() {
    }

    /**
     * Check if the watchdog heartbeat is suppressed while the open file descriptors are at or above the threshold.
     *
     * @return <code>true</code> if the file descriptors about to run out make the application unhealthy
     */
    public boolean isHealth() {
      return health;
    }

    /**
     * Enable or disable suppressing the watchdog heartbeat while the open file descriptors are at or above the threshold.
     *
     * @param health <code>true</code> to make the application unhealthy when the file descriptors are about to run out
     */
    public void setHealth(boolean health) {
      this.health = health;
    }

    /**
     * Check the share of the limit of file descriptors from which the application is unhealthy.
     *
     * @return the share of the limit, greater than 0 and at most 1
     */
    public double getThreshold() {
      return threshold;
    }

    /**
     * Set the share of the limit of file descriptors from which the application is unhealthy.
     *
     * @param threshold the share of the limit, greater than 0 and at most 1
     */
    public void setThreshold(double threshold) {
      this.threshold = threshold;
    }

    /**
     * Check the delay before suppressing the watchdog heartbeat while the open file descriptors are at or above the threshold.
     *
     * @return the delay, or <code>null</code> if there is no delay
     */
    public @Nullable Duration getUnhealthyDelay() {
      return unhealthyDelay;
    }

    /**
     * Set the delay before suppressing the watchdog heartbeat while the open file descriptors are at or above the threshold.
     *
     * @param unhealthyDelay the delay, or <code>null</code> to disable the delay
     */
    public void setUnhealthyDelay(@Nullable Duration unhealthyDelay) {
      this.unhealthyDelay = unhealthyDelay;
    }

    // rejects thresholds out of range, which would otherwise make the application always or never unhealthy
    void validate() {
      if (!(threshold > 0 && threshold <= 1)) {
        throw new InvalidConfigurationPropertyValueException(PREFIX + ".threshold", threshold, "The threshold must be greater than 0 and at most 1");
      }
      DurationUtilities.requireNotNegative(PREFIX + ".unhealthy-delay", unhealthyDelay);
    }
  }

  /**
   * The {@link AvailabilityStatusProvider}, which provides the liveness and readiness of the application.
   *
   * <p>The {@link AvailabilityStatusProvider} is always a health provider of the watchdog, while enabled, and these properties control its health: it is
   * opted into, as it lets systemd restart the application, so the application is always healthy unless enabled.
   */
  public static class Availability extends Toggle {

    private static final String PREFIX = STATUS_PROVIDERS_PREFIX + ".availability";

    /**
     * Suppress the watchdog heartbeat while the liveness state of the application is broken, so that systemd restarts it.
     */
    private boolean health;

    /**
     * Delay suppressing the watchdog heartbeat until the liveness state has been broken for this long, such as <code>30s</code>. Values without a unit
     * are in milliseconds. Not set by default, which disables the delay. Only applies with health enabled.
     */
    @Nullable
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration unhealthyDelay;

    Availability() {
    }

    /**
     * Check if the watchdog heartbeat is suppressed while the liveness state of the application is broken.
     *
     * @return <code>true</code> if a broken liveness state makes the application unhealthy
     */
    public boolean isHealth() {
      return health;
    }

    /**
     * Enable or disable suppressing the watchdog heartbeat while the liveness state of the application is broken.
     *
     * @param health <code>true</code> to make the application unhealthy while its liveness state is broken
     */
    public void setHealth(boolean health) {
      this.health = health;
    }

    /**
     * Check the delay before suppressing the watchdog heartbeat while the liveness state of the application is broken.
     *
     * @return the delay, or <code>null</code> if there is no delay
     */
    public @Nullable Duration getUnhealthyDelay() {
      return unhealthyDelay;
    }

    /**
     * Set the delay before suppressing the watchdog heartbeat while the liveness state of the application is broken.
     *
     * @param unhealthyDelay the delay, or <code>null</code> to disable the delay
     */
    public void setUnhealthyDelay(@Nullable Duration unhealthyDelay) {
      this.unhealthyDelay = unhealthyDelay;
    }

    void validate() {
      DurationUtilities.requireNotNegative(PREFIX + ".unhealthy-delay", unhealthyDelay);
    }
  }

  /**
   * The {@link HikariStatusProvider}, which provides information regarding the HikariCP connection pools.
   *
   * <p>The {@link HikariStatusProvider} is always a health provider of the watchdog, while enabled, and these properties control its health: it is opted
   * into, as it lets systemd restart the application, so the application is always healthy unless enabled.
   */
  public static class Hikari extends Toggle {

    private static final String PREFIX = STATUS_PROVIDERS_PREFIX + ".hikari";

    /**
     * Suppress the watchdog heartbeat while any connection pool is starved, with all its connections in use and threads waiting for one, so that systemd
     * restarts the application.
     */
    private boolean health;

    /**
     * Delay suppressing the watchdog heartbeat until a connection pool has been starved for this long, such as <code>60s</code> or <code>5m</code>, so
     * that a burst of requests does not restart the application. Values without a unit are in milliseconds. Only applies with health enabled. With a delay
     * of <code>0</code>, the application is restarted as soon as a pool is starved.
     */
    @Nullable
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration unhealthyDelay = Duration.ofMinutes(1);

    Hikari() {
    }

    /**
     * Check if the watchdog heartbeat is suppressed while any connection pool is starved.
     *
     * @return <code>true</code> if a starved connection pool makes the application unhealthy
     */
    public boolean isHealth() {
      return health;
    }

    /**
     * Enable or disable suppressing the watchdog heartbeat while any connection pool is starved.
     *
     * @param health <code>true</code> to make the application unhealthy while any connection pool is starved
     */
    public void setHealth(boolean health) {
      this.health = health;
    }

    /**
     * Check the delay before suppressing the watchdog heartbeat while any connection pool is starved.
     *
     * @return the delay, or <code>null</code> if there is no delay
     */
    public @Nullable Duration getUnhealthyDelay() {
      return unhealthyDelay;
    }

    /**
     * Set the delay before suppressing the watchdog heartbeat while any connection pool is starved.
     *
     * @param unhealthyDelay the delay, or <code>null</code> to disable the delay
     */
    public void setUnhealthyDelay(@Nullable Duration unhealthyDelay) {
      this.unhealthyDelay = unhealthyDelay;
    }

    void validate() {
      DurationUtilities.requireNotNegative(PREFIX + ".unhealthy-delay", unhealthyDelay);
    }
  }

  /**
   * The {@link ActuatorHealthStatusProvider}, which provides the application health when using Spring Boot Actuator.
   */
  public static class ActuatorHealth extends Toggle {

    /**
     * Prefix of the properties of the Actuator health status provider.
     */
    static final String PREFIX = STATUS_PROVIDERS_PREFIX + ".actuator-health";

    /**
     * Suppress the watchdog heartbeat while the application is unhealthy, so that systemd restarts it. Disabled by default: the health is only shown in
     * the systemd status.
     */
    private boolean health;

    /**
     * Status codes from {@link Status} to consider as unhealthy. If omitted {@link Status#DOWN} is used.
     */
    private List<String> unhealthyStatusCodes = initStatusCodes();

    /**
     * Name of the Spring Boot Actuator health group whose members are shown in the systemd status and taken into account for the health signal, such as
     * <code>liveness</code>, or a group set up with <code>management.endpoint.health.group.&lt;name&gt;.include</code>. Not set by default, which takes
     * all the health contributors into account. A group that does not exist is an error, which fails the startup of the application.
     */
    @Nullable
    private String group;

    /**
     * Delay suppressing the watchdog heartbeat until the application has been unhealthy for this long, such as <code>60s</code> or <code>5m</code>, so
     * that short outages of external systems do not restart it. Values without a unit are in milliseconds. Not set by default, which disables the delay.
     * Only applies with health enabled. The systemd status always shows the actual health, without the delay.
     */
    @Nullable
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration unhealthyDelay;

    ActuatorHealth() {
    }

    private static List<String> initStatusCodes() {
      List<String> list = new ArrayList<>(1);
      list.add(Status.DOWN.getCode());
      return list;
    }

    /**
     * Check if the watchdog heartbeat is suppressed while the application is unhealthy.
     *
     * @return <code>true</code> if the application health is signalled to the watchdog
     */
    public boolean isHealth() {
      return health;
    }

    /**
     * Enable or disable suppressing the watchdog heartbeat while the application is unhealthy.
     *
     * @param health <code>true</code> to signal the application health to the watchdog
     */
    public void setHealth(boolean health) {
      this.health = health;
    }

    /**
     * Check the status codes from {@link Status} to consider as unhealthy.
     *
     * @return the status codes from {@link Status} to consider as unhealthy
     * @see #setUnhealthyStatusCodes(List)
     */
    public List<String> getUnhealthyStatusCodes() {
      return unhealthyStatusCodes;
    }

    /**
     * Set the status codes from {@link Status} to consider as unhealthy. If omitted {@link Status#DOWN} is used.
     *
     * @param unhealthyStatusCodes the status codes from {@link Status} to consider as unhealthy
     */
    public void setUnhealthyStatusCodes(List<String> unhealthyStatusCodes) {
      requireNonNull(unhealthyStatusCodes, "Unhealthy status codes must not be null");
      this.unhealthyStatusCodes = unhealthyStatusCodes;
    }

    /**
     * Check the name of the Spring Boot Actuator health group whose members are taken into account.
     *
     * @return the name of the health group, or <code>null</code> to take all the health contributors into account
     * @see #setGroup(String)
     */
    public @Nullable String getGroup() {
      return group;
    }

    /**
     * Set the name of the Spring Boot Actuator health group whose members are shown in the systemd status and taken into account for the health signal.
     *
     * @param group the name of the health group, or <code>null</code> to take all the health contributors into account
     */
    public void setGroup(@Nullable String group) {
      this.group = group == null || group.isEmpty() ? null : group;
    }

    /**
     * Check the delay before suppressing the watchdog heartbeat while the application is unhealthy.
     *
     * @return the delay, or <code>null</code> if there is no delay
     * @see #setUnhealthyDelay(Duration)
     */
    public @Nullable Duration getUnhealthyDelay() {
      return unhealthyDelay;
    }

    /**
     * Set the delay before suppressing the watchdog heartbeat while the application is unhealthy.
     *
     * @param unhealthyDelay the delay, or <code>null</code> to disable the delay
     */
    public void setUnhealthyDelay(@Nullable Duration unhealthyDelay) {
      this.unhealthyDelay = unhealthyDelay;
    }

    void validate() {
      DurationUtilities.requireNotNegative(PREFIX + ".unhealthy-delay", unhealthyDelay);
    }
  }
}
