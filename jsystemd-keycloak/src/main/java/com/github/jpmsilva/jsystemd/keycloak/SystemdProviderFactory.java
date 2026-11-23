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

package com.github.jpmsilva.jsystemd.keycloak;

import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupPressureStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupTasksStatusProvider;
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HealthProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.VirtualThreadStatusProvider;
import com.github.jpmsilva.jsystemd.keycloak.HealthReadiness.Checks;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.Application;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.storage.ImportRealmFromRepresentationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integrates Keycloak with systemd, following the life cycle of Keycloak:
 * <ol>
 *   <li>when Keycloak initializes its providers, at the start of its bootstrap, the integration starts, and the status shows
 *   {@code Startup: bootstrapping};</li>
 *   <li>while Keycloak bootstraps, the steps of the bootstrap that can be followed are shown in the status, and extend the startup timeout: each
 *   changeset of the database migration, which {@link LiquibaseProgressProviderFactory} follows, each realm that is imported, and anything else that
 *   Keycloak publishes an event for;</li>
 *   <li>once Keycloak has completed its bootstrap, which includes migrating the database and importing realms, and its HTTP server listens, systemd is
 *   told that Keycloak is ready. Either may happen last: Keycloak bootstraps once its HTTP server listens when the health endpoints are enabled, answering
 *   503 until then, and before it starts its HTTP server otherwise. When the health endpoints are enabled, Keycloak is also only ready once its readiness
 *   health checks, those of its {@code /health/ready} endpoint, are all up;</li>
 *   <li>as soon as Keycloak is told to stop, systemd is told that it is stopping, and the status shows {@code Shutdown: stopping};</li>
 *   <li>once Keycloak closes its providers, the integration stops.</li>
 * </ol>
 *
 * <p>The watchdog heartbeats are sent when the service unit sets <code>WatchdogSec</code>, taking into account the health conditions that are opted
 * into, none by default, and the status shows the status of Keycloak and of the JVM.
 *
 * @author Joao Silva
 */
public class SystemdProviderFactory implements ProviderFactory<SystemdProvider> {

  /**
   * The id of the factory, which is part of its options.
   */
  public static final String ID = "default";

  // the stage of the startup while Keycloak bootstraps, between the steps of the bootstrap that are followed
  static final String BOOTSTRAPPING = "Startup: bootstrapping";

  private static final Logger logger = LoggerFactory.getLogger(SystemdProviderFactory.class);

  // the keys of the status providers, in the order they are shown, by how useful their information is when operating Keycloak: those of the JVM are in
  // the order of the Spring Boot starter and the Quarkus extension, among which those of Keycloak take the place of their counterparts
  private static final List<String> STATUS_PROVIDERS = List.of("cluster", "heap", "old-generation", "cgroup-memory", "cgroup-pressure", "datasource",
      "threads", "virtual-threads", "file-descriptors", "cgroup-tasks", "cpu", "garbage-collection", "non-heap", "buffer-pools", "class-loader",
      "version");

  // the status providers enabled by default: those of the JVM as in the Spring Boot starter and the Quarkus extension
  private static final String DEFAULT_STATUS_PROVIDERS =
      "cluster,heap,old-generation,cgroup-memory,datasource,threads,file-descriptors,non-heap,buffer-pools,class-loader";

  private static final String STATUS_UPDATE_PERIOD_SECONDS = "status-update-period-seconds";
  private static final int DEFAULT_STATUS_UPDATE_PERIOD_SECONDS = 5;
  private static final String WATCHDOG_ENABLED = "watchdog-enabled";
  private static final String STATUS_PROVIDERS_OPTION = "status-providers";
  private static final String STARTUP_TIMEOUT_EXTENSION = "startup-timeout-extension";
  private static final String THREADS_DEADLOCK_DETECTION = "threads-deadlock-detection";
  private static final String THREADS_DEADLOCK_HEALTH = "threads-deadlock-health";
  private static final String FILE_DESCRIPTORS_HEALTH = "file-descriptors-health";
  private static final String FILE_DESCRIPTORS_THRESHOLD = "file-descriptors-threshold";
  private static final double DEFAULT_FILE_DESCRIPTORS_THRESHOLD = 0.9;
  private static final String FILE_DESCRIPTORS_UNHEALTHY_DELAY_SECONDS = "file-descriptors-unhealthy-delay-seconds";
  private static final String CGROUP_TASKS_HEALTH = "cgroup-tasks-health";
  private static final String CGROUP_TASKS_THRESHOLD = "cgroup-tasks-threshold";
  private static final double DEFAULT_CGROUP_TASKS_THRESHOLD = 0.9;
  private static final String CGROUP_TASKS_UNHEALTHY_DELAY_SECONDS = "cgroup-tasks-unhealthy-delay-seconds";
  private static final String GARBAGE_COLLECTION_HEALTH = "garbage-collection-health";
  private static final String GARBAGE_COLLECTION_THRESHOLD = "garbage-collection-threshold";
  private static final double DEFAULT_GARBAGE_COLLECTION_THRESHOLD = 0.5;
  private static final String GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS = "garbage-collection-unhealthy-delay-seconds";
  // as a burst of allocations can also keep the garbage collector busy for a while, as in the Spring Boot starter
  private static final int DEFAULT_GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS = 60;
  private static final String DATASOURCE_HEALTH = "datasource-health";
  private static final String DATASOURCE_UNHEALTHY_DELAY_SECONDS = "datasource-unhealthy-delay-seconds";
  // as a burst of requests can also starve the pool for a while, as in the Spring Boot starter for the HikariCP connection pools
  private static final int DEFAULT_DATASOURCE_UNHEALTHY_DELAY_SECONDS = 60;
  private static final String READINESS_HEALTH = "readiness-health";
  private static final String READINESS_UNHEALTHY_DELAY_SECONDS = "readiness-unhealthy-delay-seconds";
  // the readiness health checks mostly depend on what a restart of Keycloak does not fix, such as its database, so the delay is longer than the outages
  // Keycloak is expected to ride out, such as a failover of the database, or a short network partition
  private static final int DEFAULT_READINESS_UNHEALTHY_DELAY_SECONDS = 300;

  // the first and the longest wait between checks for readiness, once Keycloak has completed its bootstrap
  private static final long FIRST_READY_CHECK_DELAY_MILLIS = 50;
  private static final long MAX_READY_CHECK_DELAY_MILLIS = 500;

  // how often the readiness health checks are run for the watchdog, and how long they may run for before Keycloak is unhealthy
  private static final Duration READINESS_HEALTH_PERIOD = Duration.ofSeconds(5);
  private static final Duration READINESS_HEALTH_STUCK_AFTER = Duration.ofSeconds(30);

  // the least time between two extensions of the startup timeout for the small steps of the bootstrap, of which there can be thousands
  private static final long STEP_EXTENSION_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

  // if the HTTP server listens
  private final BooleanSupplier listening;

  // looks up the readiness health checks, which are null when Keycloak has none
  private final Supplier<@Nullable Checks> healthReadiness;

  // looks up the datasource, which is null when there is none
  private final Supplier<@Nullable AgroalDataSource> dataSource;

  // registered while Keycloak runs, for the factories of the other SPIs that follow the bootstrap
  private final StartupProgress.Follower follower = this::startupProgress;

  // set by init(), before Keycloak uses the factory
  @Nullable
  private PhaseStatusProvider phase;

  @Nullable
  private Thread stoppingHook;

  private boolean extendStartupTimeout = true;

  private boolean readinessHealth;

  @Nullable
  private Duration readinessUnhealthyDelay;

  // while Keycloak bootstraps, from init() until it has completed its bootstrap
  private volatile boolean bootstrapping;

  // the session factory of Keycloak, once it has completed its bootstrap: what Keycloak only creates while it bootstraps is only looked up from then on
  @Nullable
  private volatile KeycloakSessionFactory bootstrapped;

  // set once Keycloak has completed its bootstrap, when the health of its readiness health checks is enabled, and it has them
  @Nullable
  private volatile ReadinessHealthProvider readiness;

  // held by ready() and stopping(), which run on different threads, so that one runs entirely before the other: ready() would otherwise clear the stage
  // set by stopping() if it ran between ready() checking that Keycloak is not ready and clearing the stage of the startup; also held while the stage of
  // the startup is set, for the same reason
  private final Lock lifecycleLock = new ReentrantLock();

  // guarded by the lifecycle lock: when the startup timeout was last extended, as a value of nanoTime, or null if it never was
  @Nullable
  private Long extended;

  /**
   * Creates a new instance, as Keycloak does.
   */
  public SystemdProviderFactory() {
    this(SystemdProviderFactory::quarkusStarted, HealthReadiness::lookup, DatasourceStatusProvider::lookup);
  }

  /**
   * Creates a new instance.
   *
   * @param listening tells if the HTTP server listens
   * @param healthReadiness looks up the readiness health checks, returning null when Keycloak has none
   * @param dataSource looks up the datasource, returning null when there is none
   */
  SystemdProviderFactory(BooleanSupplier listening, Supplier<@Nullable Checks> healthReadiness, Supplier<@Nullable AgroalDataSource> dataSource) {
    this.listening = listening;
    this.healthReadiness = healthReadiness;
    this.dataSource = dataSource;
  }

  // Quarkus only starts once its HTTP server listens; outside of Quarkus, as in tests, there is no HTTP server to wait for
  private static boolean quarkusStarted() {
    Application application = Application.currentApplication();
    return application == null || application.isStarted();
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public SystemdProvider create(KeycloakSession session) {
    return new SystemdProvider() {
    };
  }

  /**
   * Starts the integration with systemd, when Keycloak initializes its providers, at the start of its bootstrap.
   *
   * @param config the options of the factory
   */
  @Override
  public void init(Config.Scope config) {
    Systemd systemd = configure(config);
    systemd.logStatus();
    PhaseStatusProvider phase = new PhaseStatusProvider(true);
    // the stage of the life cycle comes first; the other providers are only asked when running under systemd
    systemd.addStatusProviders(phase);
    if (Systemd.isUnderSystemd()) {
      Providers providers = providers(config);
      systemd.addStatusProviders(providers.status());
      systemd.addHealthProviders(providers.health());
    }
    extendStartupTimeout = flag(config, STARTUP_TIMEOUT_EXTENSION, true);
    readinessHealth = flag(config, READINESS_HEALTH, false);
    readinessUnhealthyDelay = delay(config, READINESS_UNHEALTHY_DELAY_SECONDS, DEFAULT_READINESS_UNHEALTHY_DELAY_SECONDS);
    this.phase = phase;
    phase.setPhase(BOOTSTRAPPING);
    bootstrapping = true;
    StartupProgress.follow(follower);
    // a shutdown hook runs as soon as the JVM is told to stop, before Keycloak starts shutting down, which only closes the providers later
    Thread stoppingHook = new Thread(this::stopping, "jsystemd-stopping");
    Runtime.getRuntime().addShutdownHook(stoppingHook);
    this.stoppingHook = stoppingHook;
  }

  private static Systemd configure(Config.Scope config) {
    Systemd systemd = Systemd.instance();
    // nothing is scheduled when not running under systemd, as nothing would be sent
    if (!Systemd.isUnderSystemd()) {
      return systemd;
    }
    systemd.enableStatusUpdate(statusUpdatePeriod(config));
    if (flag(config, WATCHDOG_ENABLED, true)) {
      systemd.enableWatchdog();
    }
    return systemd;
  }

  /**
   * Returns the period of the status updates, in the {@code status-update-period-seconds} option: one that is not positive is ignored, with a warning, as
   * are unknown status providers, rather than failing the startup of Keycloak.
   *
   * @param config the configuration of the provider
   * @return the period of the status updates
   */
  static Duration statusUpdatePeriod(Config.Scope config) {
    return Duration.ofSeconds(seconds(config, STATUS_UPDATE_PERIOD_SECONDS, DEFAULT_STATUS_UPDATE_PERIOD_SECONDS, 1));
  }

  // an option that cannot be read, as when it is not of its type, is ignored, with a warning, as are the values out of range, rather than failing the
  // startup of Keycloak
  private static boolean flag(Config.Scope config, String option, boolean defaultValue) {
    try {
      Boolean value = config.getBoolean(option, defaultValue);
      return value == null ? defaultValue : value;
    } catch (RuntimeException e) {
      // without the stack trace, which tells nothing more
      logger.warn("Ignoring the option {}, which is neither true nor false, using {} instead ({})", option, defaultValue, e.getMessage());
      return defaultValue;
    }
  }

  private static int seconds(Config.Scope config, String option, int defaultSeconds, int leastSeconds) {
    Integer seconds;
    try {
      seconds = config.getInt(option, defaultSeconds);
    } catch (RuntimeException e) {
      logger.warn("Ignoring the option {}, which is not a number of seconds, using {} seconds instead ({})", option, defaultSeconds, e.getMessage());
      return defaultSeconds;
    }
    if (seconds == null) {
      return defaultSeconds;
    }
    if (seconds < leastSeconds) {
      logger.warn("Ignoring the option {} of {} seconds, which must be of at least {}, using {} seconds instead", option, seconds, leastSeconds,
          defaultSeconds);
      return defaultSeconds;
    }
    return seconds;
  }

  // the delay of an unhealthy state, from an option in seconds, or null for no delay, when it is 0
  private static @Nullable Duration delay(Config.Scope config, String option, int defaultSeconds) {
    int seconds = seconds(config, option, defaultSeconds, 0);
    return seconds == 0 ? null : Duration.ofSeconds(seconds);
  }

  // the share from which Keycloak is unhealthy, such as of the limit of file descriptors
  private static double threshold(Config.Scope config, String option, double defaultThreshold) {
    String value = config.get(option);
    if (value == null) {
      return defaultThreshold;
    }
    try {
      double threshold = Double.parseDouble(value.trim());
      if (threshold > 0 && threshold <= 1) {
        return threshold;
      }
    } catch (NumberFormatException e) {
      logger.debug("The option {} is not a number: {}", option, value, e);
    }
    logger.warn("Ignoring the option {} of \"{}\", which must be greater than 0 and at most 1, using {} instead", option, value, defaultThreshold);
    return defaultThreshold;
  }

  /**
   * The providers set up from the options of the factory.
   *
   * @param status the status providers that are enabled, in the order they are shown
   * @param health the health providers whose health is enabled, which the watchdog asks whether they are shown in the status or not
   */
  record Providers(List<StatusProvider> status, List<HealthProvider> health) {

  }

  /**
   * Returns the status providers that are enabled, in the order they are shown, and the health providers whose health is enabled.
   *
   * @param config the options of the factory
   * @return the providers
   */
  Providers providers(Config.Scope config) {
    final List<HealthProvider> health = new ArrayList<>();

    boolean deadlockDetection = flag(config, THREADS_DEADLOCK_DETECTION, false);
    boolean deadlockHealth = flag(config, THREADS_DEADLOCK_HEALTH, false);
    if (deadlockHealth && !deadlockDetection) {
      logger.warn("Ignoring the option {}, as signalling deadlocks requires detecting them: also set {}", THREADS_DEADLOCK_HEALTH,
          THREADS_DEADLOCK_DETECTION);
      deadlockHealth = false;
    }
    final ThreadStatusProvider threads = new ThreadStatusProvider(deadlockDetection, deadlockHealth);
    if (deadlockHealth) {
      health.add(threads);
    }

    boolean fileDescriptorsHealth = flag(config, FILE_DESCRIPTORS_HEALTH, false);
    final FileDescriptorStatusProvider fileDescriptors = new FileDescriptorStatusProvider(fileDescriptorsHealth,
        threshold(config, FILE_DESCRIPTORS_THRESHOLD, DEFAULT_FILE_DESCRIPTORS_THRESHOLD));
    fileDescriptors.setUnhealthyDelay(delay(config, FILE_DESCRIPTORS_UNHEALTHY_DELAY_SECONDS, 0));
    if (fileDescriptorsHealth) {
      health.add(fileDescriptors);
    }

    boolean cgroupTasksHealth = flag(config, CGROUP_TASKS_HEALTH, false);
    final CgroupTasksStatusProvider cgroupTasks = new CgroupTasksStatusProvider(cgroupTasksHealth,
        threshold(config, CGROUP_TASKS_THRESHOLD, DEFAULT_CGROUP_TASKS_THRESHOLD));
    cgroupTasks.setUnhealthyDelay(delay(config, CGROUP_TASKS_UNHEALTHY_DELAY_SECONDS, 0));
    if (cgroupTasksHealth) {
      health.add(cgroupTasks);
    }

    boolean garbageCollectionHealth = flag(config, GARBAGE_COLLECTION_HEALTH, false);
    final GarbageCollectionStatusProvider garbageCollection = new GarbageCollectionStatusProvider(garbageCollectionHealth,
        threshold(config, GARBAGE_COLLECTION_THRESHOLD, DEFAULT_GARBAGE_COLLECTION_THRESHOLD));
    garbageCollection.setUnhealthyDelay(delay(config, GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS, DEFAULT_GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS));
    if (garbageCollectionHealth) {
      health.add(garbageCollection);
    }

    boolean datasourceHealth = flag(config, DATASOURCE_HEALTH, false);
    // the datasource is only looked up once Keycloak has completed its bootstrap, rather than have a status update create it before Keycloak does
    final DatasourceStatusProvider datasource = new DatasourceStatusProvider(() -> bootstrapped == null ? null : dataSource.get(), datasourceHealth);
    datasource.setUnhealthyDelay(delay(config, DATASOURCE_UNHEALTHY_DELAY_SECONDS, DEFAULT_DATASOURCE_UNHEALTHY_DELAY_SECONDS));
    if (datasourceHealth) {
      health.add(datasource);
    }

    String[] configured = config.getArray(STATUS_PROVIDERS_OPTION);
    List<String> enabled = Arrays.stream(configured == null ? DEFAULT_STATUS_PROVIDERS.split(",") : configured).map(String::trim).toList();
    enabled.stream().filter(key -> !STATUS_PROVIDERS.contains(key))
        .forEach(key -> logger.warn("Ignoring unknown status provider \"{}\", known ones are {}", key, STATUS_PROVIDERS));
    List<StatusProvider> status = new ArrayList<>();
    for (String key : STATUS_PROVIDERS) {
      if (enabled.contains(key)) {
        status.add(switch (key) {
          case "cluster" -> new ClusterStatusProvider(() -> bootstrapped);
          case "heap" -> new HeapStatusProvider();
          case "old-generation" -> new OldGenerationStatusProvider();
          case "cgroup-memory" -> new CgroupMemoryStatusProvider();
          case "cgroup-pressure" -> new CgroupPressureStatusProvider();
          case "datasource" -> datasource;
          case "threads" -> threads;
          case "virtual-threads" -> new VirtualThreadStatusProvider();
          case "file-descriptors" -> fileDescriptors;
          case "cgroup-tasks" -> cgroupTasks;
          case "cpu" -> new CpuStatusProvider();
          case "garbage-collection" -> garbageCollection;
          case "non-heap" -> new NonHeapStatusProvider();
          case "buffer-pools" -> new BufferPoolStatusProvider();
          case "class-loader" -> new ClassLoaderStatusProvider();
          case "version" -> new VersionStatusProvider();
          default -> throw new IllegalStateException("No status provider for " + key);
        });
      }
    }
    return new Providers(status, health);
  }

  /**
   * Follows the bootstrap of Keycloak, and tells systemd that Keycloak is ready once it has completed its bootstrap, and its HTTP server listens.
   *
   * @param factory the session factory of Keycloak
   */
  @Override
  public void postInit(KeycloakSessionFactory factory) {
    factory.register(event -> {
      // published at the end of the bootstrap, right before Keycloak considers its bootstrap completed
      if (event instanceof PostMigrationEvent) {
        bootstrapped = factory;
        bootstrapCompleted();
      } else {
        bootstrapProgress(event);
      }
    });
  }

  // any event published while Keycloak bootstraps tells that the bootstrap makes progress, such as those of the realms, the clients and the roles it
  // imports; never fails, as it is called from within the bootstrap, which it must not break
  private void bootstrapProgress(ProviderEvent event) {
    if (!bootstrapping) {
      return;
    }
    try {
      if (event instanceof ImportRealmFromRepresentationEvent importing) {
        RealmRepresentation realm = importing.getRealmRepresentation();
        String name = realm == null ? null : realm.getRealm();
        startupProgress(name == null ? "Startup: importing a realm" : "Startup: importing realm " + name, true);
      } else {
        startupProgress(null, false);
      }
    } catch (RuntimeException e) {
      logger.debug("Could not follow the bootstrap of Keycloak", e);
    }
  }

  /**
   * Follows a step of the bootstrap of Keycloak, showing it in the stage of the startup, and extending the startup timeout, unless disabled, so that
   * systemd does not time out a startup that makes progress: on every step that may take long, and at most once a second on the others. Does nothing
   * once Keycloak has completed its bootstrap, or is stopping: what Keycloak waits for from then on is not progress, and extending the timeout would
   * then extend the shutdown.
   *
   * @param stage the stage of the startup to show, or null to keep the one shown
   * @param milestone whether the step is one that may take long, rather than one of many small ones
   */
  void startupProgress(@Nullable String stage, boolean milestone) {
    PhaseStatusProvider phase = this.phase;
    lifecycleLock.lock();
    try {
      Systemd systemd = Systemd.instance();
      if (phase == null || !bootstrapping || systemd.isReady()) {
        return;
      }
      // only when it changes, as each change of the stage sends the status
      if (stage != null && !stage.equals(phase.getPhase())) {
        phase.setPhase(stage);
      }
      if (extendStartupTimeout) {
        // values of nanoTime are only compared by their difference, as they may overflow
        long now = System.nanoTime();
        Long extended = this.extended;
        if (milestone || extended == null || now - extended >= STEP_EXTENSION_PERIOD_NANOS) {
          this.extended = now;
          systemd.extendTimeout();
        }
      }
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Tells systemd that Keycloak is ready, once its readiness health checks are all up when it has them, and once its HTTP server listens.
   */
  void bootstrapCompleted() {
    bootstrapping = false;
    Checks checks = healthReadiness.get();
    if (checks == null) {
      if (readinessHealth) {
        logger.warn("The health of the readiness health checks is enabled, but Keycloak has none, so they never make it unhealthy: build Keycloak with "
            + "its health endpoints enabled (health-enabled=true)");
      }
      logger.info("Telling systemd that Keycloak is ready once it has completed its bootstrap and its HTTP server listens");
      if (listening.getAsBoolean()) {
        ready();
      } else {
        // the bootstrap runs while Quarkus starts, which only starts the HTTP server once it completes
        readyWhen(this::waitingForTheHttpServer);
      }
    } else {
      if (readinessHealth) {
        // the checks keep running once Keycloak is ready, for the watchdog, which only asks for the health from then on
        ReadinessHealthProvider readiness = new ReadinessHealthProvider(checks, READINESS_HEALTH_PERIOD, READINESS_HEALTH_STUCK_AFTER);
        readiness.setUnhealthyDelay(readinessUnhealthyDelay);
        Systemd.instance().addHealthProviders(readiness);
        this.readiness = readiness;
      }
      // the checks are not up yet, as Keycloak only considers its bootstrap completed once the event has been published
      logger.info("Telling systemd that Keycloak is ready once its readiness health checks are all up");
      readyWhen(() -> {
        String waiting = waitingForTheHttpServer();
        return waiting != null ? waiting : waitingFor(checks);
      });
    }
  }

  private @Nullable String waitingForTheHttpServer() {
    return listening.getAsBoolean() ? null : "the HTTP server";
  }

  private static @Nullable String waitingFor(Checks checks) {
    List<String> down = checks.down();
    if (down == null) {
      return null;
    }
    return "the readiness health checks" + (down.isEmpty() ? "" : " (down: " + String.join(", ", down) + ")");
  }

  // the waiting is done on a thread of its own, so that neither the bootstrap nor the startup of Quarkus wait for it
  private void readyWhen(Supplier<@Nullable String> waitingFor) {
    Thread waiting = new Thread(() -> waitUntilReady(waitingFor), "jsystemd-ready");
    waiting.setDaemon(true);
    waiting.start();
  }

  // waits until there is nothing left to wait for, showing what Keycloak waits for in the stage of the startup, so that a startup that does not
  // complete, which systemd times out, tells why
  private void waitUntilReady(Supplier<@Nullable String> waitingFor) {
    Systemd systemd = Systemd.instance();
    long delay = FIRST_READY_CHECK_DELAY_MILLIS;
    String shown = null;
    try {
      // neither Quarkus nor the health checks have events that a provider can observe; the wait ends if Keycloak stops instead, which ends the
      // startup, and so before the checks can no longer run
      String waiting;
      do {
        Thread.sleep(delay);
        delay = Math.min(delay * 2, MAX_READY_CHECK_DELAY_MILLIS);
        if (systemd.isReady()) {
          return;
        }
        waiting = waitingFor.get();
        // only when it changes, as each change of the stage sends the status
        if (waiting != null && !waiting.equals(shown)) {
          shown = waiting;
          startupStage("Startup: waiting for " + waiting);
        }
      } while (waiting != null);
      ready();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // sets the stage of the startup, unless Keycloak is ready or stopping, holding the lock of ready() and stopping(), so as not to replace their stages
  private void startupStage(String stage) {
    PhaseStatusProvider phase = this.phase;
    lifecycleLock.lock();
    try {
      if (phase != null && !Systemd.instance().isReady()) {
        phase.setPhase(stage);
      }
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Tells systemd that Keycloak is ready. Does nothing once Keycloak is stopping, nor if already ready.
   */
  void ready() {
    Systemd systemd = Systemd.instance();
    PhaseStatusProvider phase = this.phase;
    lifecycleLock.lock();
    try {
      // the stage is only cleared by the call that tells systemd, so that a stopping stage is never cleared
      if (phase != null && !systemd.isReady()) {
        phase.clearPhase();
        systemd.ready();
        ReadinessHealthProvider readiness = this.readiness;
        if (readiness != null) {
          readiness.start();
        }
      }
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Tells systemd that Keycloak is stopping. From then on, no more watchdog heartbeats are sent.
   */
  void stopping() {
    PhaseStatusProvider phase = this.phase;
    lifecycleLock.lock();
    try {
      if (phase != null) {
        // heartbeats are stopped before anything else, see Systemd.stopping()
        Systemd.instance().stopping();
        phase.setPhase("Shutdown: stopping");
        // the watchdog no longer asks for the health
        ReadinessHealthProvider readiness = this.readiness;
        if (readiness != null) {
          readiness.stop();
        }
      }
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Stops the integration with systemd, once Keycloak closes its providers, telling systemd that Keycloak is stopping if not done before.
   */
  @Override
  public void close() {
    StartupProgress.unfollow(follower);
    Thread stoppingHook = this.stoppingHook;
    if (stoppingHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(stoppingHook);
      } catch (IllegalStateException e) {
        // the JVM is shutting down, and has already run the hook, or is running it
        logger.trace("Not removing the shutdown hook, as the JVM is shutting down", e);
      }
    }
    ReadinessHealthProvider readiness = this.readiness;
    if (readiness != null) {
      readiness.stop();
    }
    if (phase != null) {
      try {
        Systemd.instance().close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public List<ProviderConfigProperty> getConfigMetadata() {
    return ProviderConfigurationBuilder.create()
        .property().name(STATUS_UPDATE_PERIOD_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE).defaultValue(DEFAULT_STATUS_UPDATE_PERIOD_SECONDS)
        .helpText("How often the status is sent to systemd, in seconds.").add()
        .property().name(WATCHDOG_ENABLED).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(true)
        .helpText("Whether watchdog heartbeats are sent, when the service unit sets WatchdogSec, at half that interval.").add()
        .property().name(STATUS_PROVIDERS_OPTION).type(ProviderConfigProperty.STRING_TYPE).defaultValue(DEFAULT_STATUS_PROVIDERS)
        .helpText("The status providers shown, separated by commas, among " + String.join(", ", STATUS_PROVIDERS) + ".").add()
        .property().name(STARTUP_TIMEOUT_EXTENSION).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(true)
        .helpText("Whether the startup timeout is extended as the bootstrap makes progress, such as on each changeset of the database migration.").add()
        .property().name(THREADS_DEADLOCK_DETECTION).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether deadlocked threads are detected, and reported in the status.").add()
        .property().name(THREADS_DEADLOCK_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while any thread is deadlocked, so that systemd restarts Keycloak. Requires detecting "
            + "deadlocked threads.").add()
        .property().name(FILE_DESCRIPTORS_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while the open file descriptors are at or above the threshold.").add()
        .property().name(FILE_DESCRIPTORS_THRESHOLD).type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(String.valueOf(DEFAULT_FILE_DESCRIPTORS_THRESHOLD))
        .helpText("The share of the limit of file descriptors from which Keycloak is unhealthy, greater than 0 and at most 1.").add()
        .property().name(FILE_DESCRIPTORS_UNHEALTHY_DELAY_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE).defaultValue(0)
        .helpText("How long the file descriptors must stay at or above the threshold before the watchdog heartbeat is suppressed, in seconds.").add()
        .property().name(CGROUP_TASKS_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while the tasks of the control group are at or above the threshold.").add()
        .property().name(CGROUP_TASKS_THRESHOLD).type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(String.valueOf(DEFAULT_CGROUP_TASKS_THRESHOLD))
        .helpText("The share of the limit of tasks from which Keycloak is unhealthy, greater than 0 and at most 1.").add()
        .property().name(CGROUP_TASKS_UNHEALTHY_DELAY_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE).defaultValue(0)
        .helpText("How long the tasks must stay at or above the threshold before the watchdog heartbeat is suppressed, in seconds.").add()
        .property().name(GARBAGE_COLLECTION_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while the garbage collection pauses take the threshold of the time or more.").add()
        .property().name(GARBAGE_COLLECTION_THRESHOLD).type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(String.valueOf(DEFAULT_GARBAGE_COLLECTION_THRESHOLD))
        .helpText("The share of the time taken by the garbage collection pauses from which Keycloak is unhealthy, greater than 0 and at most 1.").add()
        .property().name(GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE)
        .defaultValue(DEFAULT_GARBAGE_COLLECTION_UNHEALTHY_DELAY_SECONDS)
        .helpText("How long the garbage collection pauses must take the threshold of the time or more before the watchdog heartbeat is suppressed, in "
            + "seconds.").add()
        .property().name(DATASOURCE_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while the connection pool of the datasource is starved. Requires the metrics of "
            + "Keycloak.").add()
        .property().name(DATASOURCE_UNHEALTHY_DELAY_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE)
        .defaultValue(DEFAULT_DATASOURCE_UNHEALTHY_DELAY_SECONDS)
        .helpText("How long the connection pool of the datasource must stay starved before the watchdog heartbeat is suppressed, in seconds.").add()
        .property().name(READINESS_HEALTH).type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue(false)
        .helpText("Whether the watchdog heartbeat is suppressed while any readiness health check is down. Requires the health endpoints of "
            + "Keycloak.").add()
        .property().name(READINESS_UNHEALTHY_DELAY_SECONDS).type(ProviderConfigProperty.INTEGER_TYPE)
        .defaultValue(DEFAULT_READINESS_UNHEALTHY_DELAY_SECONDS)
        .helpText("How long the readiness health checks must stay down before the watchdog heartbeat is suppressed, in seconds.").add()
        .build();
  }

  // for tests: the stage of the life cycle shown in the status, or null before init()
  @Nullable
  String phase() {
    PhaseStatusProvider phase = this.phase;
    return phase == null ? null : phase.getPhase();
  }
}
