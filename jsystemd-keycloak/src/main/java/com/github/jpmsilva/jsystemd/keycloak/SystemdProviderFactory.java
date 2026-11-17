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
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.CpuStatusProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.GarbageCollectionStatusProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.keycloak.HealthReadiness.Checks;
import io.quarkus.runtime.Application;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.keycloak.provider.ProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integrates Keycloak with systemd, following the life cycle of Keycloak:
 * <ol>
 *   <li>when Keycloak initializes its providers, at the start of its bootstrap, the integration starts, and the status shows
 *   {@code Startup: bootstrapping};</li>
 *   <li>once Keycloak has completed its bootstrap, which includes migrating the database and importing realms, and its HTTP server listens, systemd is
 *   told that Keycloak is ready. Either may happen last: Keycloak bootstraps once its HTTP server listens when the health endpoints are enabled, answering
 *   503 until then, and before it starts its HTTP server otherwise. When the health endpoints are enabled, Keycloak is also only ready once its readiness
 *   health checks, those of its {@code /health/ready} endpoint, are all up;</li>
 *   <li>as soon as Keycloak is told to stop, systemd is told that it is stopping, and the status shows {@code Shutdown: stopping};</li>
 *   <li>once Keycloak closes its providers, the integration stops.</li>
 * </ol>
 *
 * <p>The watchdog heartbeats are sent when the service unit sets <code>WatchdogSec</code>, and the status shows the status of the JVM.
 *
 * @author Joao Silva
 */
public class SystemdProviderFactory implements ProviderFactory<SystemdProvider> {

  /**
   * The id of the factory, which is part of its options.
   */
  public static final String ID = "default";

  private static final Logger logger = LoggerFactory.getLogger(SystemdProviderFactory.class);

  // the status providers by key, in the order they are shown, as in the Spring Boot starter and the Quarkus extension
  private static final Map<String, Supplier<StatusProvider>> STATUS_PROVIDERS = knownStatusProviders();

  // the status providers enabled by default, as in the Spring Boot starter and the Quarkus extension
  private static final String DEFAULT_STATUS_PROVIDERS = "heap,old-generation,cgroup-memory,threads,file-descriptors,non-heap,buffer-pools,class-loader";

  private static final String STATUS_UPDATE_PERIOD_SECONDS = "status-update-period-seconds";
  private static final int DEFAULT_STATUS_UPDATE_PERIOD_SECONDS = 5;
  private static final String WATCHDOG_ENABLED = "watchdog-enabled";
  private static final String STATUS_PROVIDERS_OPTION = "status-providers";

  // the first and the longest wait between checks for readiness, once Keycloak has completed its bootstrap
  private static final long FIRST_READY_CHECK_DELAY_MILLIS = 50;
  private static final long MAX_READY_CHECK_DELAY_MILLIS = 500;

  // if the HTTP server listens
  private final BooleanSupplier listening;

  // looks up the readiness health checks, which are null when Keycloak has none
  private final Supplier<@Nullable Checks> healthReadiness;

  // set by init(), before Keycloak uses the factory
  @Nullable
  private PhaseStatusProvider phase;

  @Nullable
  private Thread stoppingHook;

  // held by ready() and stopping(), which run on different threads, so that one runs entirely before the other: ready() would otherwise clear the stage
  // set by stopping() if it ran between ready() checking that Keycloak is not ready and clearing the stage of the startup; also held while the stage of
  // the startup is set, for the same reason
  private final Lock lifecycleLock = new ReentrantLock();

  /**
   * Creates a new instance, as Keycloak does.
   */
  public SystemdProviderFactory() {
    this(SystemdProviderFactory::quarkusStarted, HealthReadiness::lookup);
  }

  /**
   * Creates a new instance.
   *
   * @param listening tells if the HTTP server listens
   * @param healthReadiness looks up the readiness health checks, returning null when Keycloak has none
   */
  SystemdProviderFactory(BooleanSupplier listening, Supplier<@Nullable Checks> healthReadiness) {
    this.listening = listening;
    this.healthReadiness = healthReadiness;
  }

  // Quarkus only starts once its HTTP server listens; outside of Quarkus, as in tests, there is no HTTP server to wait for
  private static boolean quarkusStarted() {
    Application application = Application.currentApplication();
    return application == null || application.isStarted();
  }

  private static Map<String, Supplier<StatusProvider>> knownStatusProviders() {
    Map<String, Supplier<StatusProvider>> providers = new LinkedHashMap<>();
    providers.put("heap", HeapStatusProvider::new);
    providers.put("old-generation", OldGenerationStatusProvider::new);
    providers.put("cgroup-memory", CgroupMemoryStatusProvider::new);
    providers.put("threads", () -> new ThreadStatusProvider(false, false));
    providers.put("file-descriptors", () -> new FileDescriptorStatusProvider(false, 0.9));
    providers.put("cpu", CpuStatusProvider::new);
    providers.put("garbage-collection", GarbageCollectionStatusProvider::new);
    providers.put("non-heap", NonHeapStatusProvider::new);
    providers.put("buffer-pools", BufferPoolStatusProvider::new);
    providers.put("class-loader", ClassLoaderStatusProvider::new);
    return providers;
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
    // the stage of the life cycle comes first; the other status providers are only asked when running under systemd
    systemd.addStatusProviders(phase);
    if (Systemd.isUnderSystemd()) {
      systemd.addStatusProviders(statusProviders(config));
    }
    this.phase = phase;
    phase.setPhase("Startup: bootstrapping");
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
    if (config.getBoolean(WATCHDOG_ENABLED, true)) {
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
    int seconds = config.getInt(STATUS_UPDATE_PERIOD_SECONDS, DEFAULT_STATUS_UPDATE_PERIOD_SECONDS);
    if (seconds < 1) {
      logger.warn("Ignoring the status update period of {} seconds, which must be of at least a second, using {} seconds instead", seconds,
          DEFAULT_STATUS_UPDATE_PERIOD_SECONDS);
      seconds = DEFAULT_STATUS_UPDATE_PERIOD_SECONDS;
    }
    return Duration.ofSeconds(seconds);
  }

  /**
   * Returns the status providers that are enabled, in the order they are shown.
   *
   * @param config the options of the factory
   * @return the status providers that are enabled
   */
  static List<StatusProvider> statusProviders(Config.Scope config) {
    String[] configured = config.getArray(STATUS_PROVIDERS_OPTION);
    List<String> enabled = Arrays.stream(configured == null ? DEFAULT_STATUS_PROVIDERS.split(",") : configured).map(String::trim).toList();
    enabled.stream().filter(key -> !STATUS_PROVIDERS.containsKey(key))
        .forEach(key -> logger.warn("Ignoring unknown status provider \"{}\", known ones are {}", key, STATUS_PROVIDERS.keySet()));
    List<StatusProvider> providers = new ArrayList<>();
    STATUS_PROVIDERS.forEach((key, provider) -> {
      if (enabled.contains(key)) {
        providers.add(provider.get());
      }
    });
    return providers;
  }

  /**
   * Tells systemd that Keycloak is ready once it has completed its bootstrap, and its HTTP server listens.
   *
   * @param factory the session factory of Keycloak
   */
  @Override
  public void postInit(KeycloakSessionFactory factory) {
    // published at the end of the bootstrap, right before Keycloak considers its bootstrap completed
    factory.register(event -> {
      if (event instanceof PostMigrationEvent) {
        bootstrapCompleted();
      }
    });
  }

  /**
   * Tells systemd that Keycloak is ready, once its readiness health checks are all up when it has them, and once its HTTP server listens.
   */
  void bootstrapCompleted() {
    Checks checks = healthReadiness.get();
    if (checks == null) {
      logger.info("Telling systemd that Keycloak is ready once it has completed its bootstrap and its HTTP server listens");
      if (listening.getAsBoolean()) {
        ready();
      } else {
        // the bootstrap runs while Quarkus starts, which only starts the HTTP server once it completes
        readyWhen(this::waitingForTheHttpServer);
      }
    } else {
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
    Thread stoppingHook = this.stoppingHook;
    if (stoppingHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(stoppingHook);
      } catch (IllegalStateException e) {
        // the JVM is shutting down, and has already run the hook, or is running it
        logger.trace("Not removing the shutdown hook, as the JVM is shutting down", e);
      }
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
        .helpText("The status providers shown, separated by commas, among " + String.join(", ", STATUS_PROVIDERS.keySet()) + ".").add()
        .build();
  }

  // for tests: the stage of the life cycle shown in the status, or null before init()
  @Nullable
  String phase() {
    PhaseStatusProvider phase = this.phase;
    return phase == null ? null : phase.getPhase();
  }
}
