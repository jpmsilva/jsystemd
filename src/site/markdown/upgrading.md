# Upgrading

## From 5.0 to 5.1

### Changes in behaviour

* A deadlock, with deadlock health opted into, and a broken liveness state under Spring Boot, with `systemd.status-providers.availability.health=true`,
  now trigger the watchdog right away, instead of only suppressing the heartbeat until `WatchdogSec` elapses, as the application cannot recover from
  either (see [Unrecoverable states](health-providers.html#Unrecoverable_states)). An application that reports a broken liveness state and then recovers
  from it must set `systemd.status-providers.availability.unhealthy-delay`. Triggering the watchdog requires systemd 243 or later: older versions
  restart the application once `WatchdogSec` elapses, as before.
* [HealthProvider.Health](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html) has a third component, `unrecoverable`. Creating it with
  `new Health(healthy, details)` still works; code that takes it apart with a record pattern must add the component.
* Under Spring Boot, the status has new parts by default when their libraries are used: the progress of the Flyway migrations, which also extend the
  startup timeout, and the Kafka, RabbitMQ and JMS listener containers. Disable them with `systemd.status-providers.<key>.enabled=false` (see
  [Status providers](status-providers.html)).
* Under Spring Boot, the `tomcat`, `jetty` and `garbage-collection` status providers are now also health providers, registered with the watchdog while
  enabled, and always healthy unless opted into. In
  [SystemdProperties](apidocs/com/github/jpmsilva/jsystemd/spring/autoconfigure/SystemdProperties.html), their settings are no longer a plain `Toggle`,
  but subclasses of it.
* Under Keycloak, the status also shows the cluster of Keycloak, first, such as `Cluster: 3 nodes`, and the connection pool of its datasource when
  Keycloak is built with `metrics-enabled`: set `--spi-systemd--default--status-providers` to leave them out.
* Under Keycloak, the startup timeout is now extended while the bootstrap makes progress, such as on each changeset of a database migration. For that,
  the provider adds a Liquibase connection provider, which makes `kc.sh build` log the warning `KC-SERVICES0047` about the internal SPI
  `connectionsLiquibase`: disable it with `--spi-connections-liquibase--jsystemd--enabled=false` (see [Keycloak](keycloak.html#Startup_progress)).
* The built-in status provider beans of the starter are no longer all 1000 apart, as the new ones were placed between them (see
  [Status providers](status-providers.html#Custom_status_providers_under_Spring_Boot)).

### Other changes

* The watchdog can be triggered, with [Health.unrecoverable(...)](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html) from a health
  provider, or with [Systemd.triggerWatchdog()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).
* New status providers in the core library, for the virtual threads, and for the pressure and the tasks of the control group of the service, the latter
  with a health condition; the garbage collection one has a health condition as well (see [Status providers](status-providers.html) and
  [Health providers](health-providers.html)). Under Spring Boot, they are `virtual-threads`, `cgroup-pressure` and `cgroup-tasks`, all opted into.
* New status providers under Spring Boot: `flyway`, `kafka-listeners`, `rabbit-listeners` and `jms-listeners`, enabled by default when their libraries
  are used, and `r2dbc`, `scheduled-tasks`, `micrometer` and `version`, which are opted into.
* New health conditions under Spring Boot, all opted into: a saturated Tomcat or Jetty, a starved R2DBC connection pool, and a message listener
  container that stopped by itself.
* Under Keycloak: the progress of the bootstrap in the status, new `cluster`, `datasource`, `version`, `virtual-threads`, `cgroup-pressure` and
  `cgroup-tasks` status providers, and health conditions that are opted into, for deadlocks, file descriptors, tasks, garbage collection, a starved
  datasource and the readiness health checks (see [Keycloak](keycloak.html)). Options that cannot be read are ignored, with a warning.

## From 4.x to 5.0

### Breaking changes

#### Health provider and watchdog (Spring Boot)

Showing the application health in the systemd status and reporting it to the watchdog are configured separately:

* the [Actuator health status](status-providers.html) is **shown by default** when Spring Boot Actuator is available; disable it with
  `systemd.status-providers.actuator-health.enabled=false`;
* reporting the health to the watchdog is still **opt-in** (see [Health providers](health-providers.html));
* [HealthProvider](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.html) beans of the application are used together with the built-in ones, instead
  of replacing the Actuator one;
* the properties moved under `systemd.status-providers.actuator-health`, and the old ones are ignored:

  | 4.x                                                   | 5.0                                                               |
  |-------------------------------------------------------|-------------------------------------------------------------------|
  | `systemd.health-provider.enabled`                     | `systemd.status-providers.actuator-health.health`                 |
  | `systemd.health-provider.unhealthy-status-codes`      | `systemd.status-providers.actuator-health.unhealthy-status-codes` |
  | `systemd.health-provider.unhealthy-pending-period-ms` | `systemd.status-providers.actuator-health.unhealthy-delay`        |

  `unhealthy-delay` is a
  [duration](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.conversion.durations)
  such as `60s`; values without a unit are still in milliseconds.

#### Systemd status

* Health: the status only names the unhealthy health contributors, such as `health status: unhealthy (db, redis)`, without their details, which any local
  user can read. The details are logged when the watchdog heartbeat is suppressed.
* Tomcat: the [Tomcat status provider](status-providers.html#tomcat) shows the connections and busy threads of each connector, such as
  `http-nio-8080: 8/8192 connections, 3/10 threads`, and no longer needs `server.tomcat.mbeanregistry.enabled=true`.
* New status providers, enabled by default, show more in the status. Disable the ones you do not need (see [Status providers](status-providers.html)),
  such as:

  ```properties
  systemd.status-providers.buffer-pools.enabled=false
  systemd.status-providers.task-executors.enabled=false
  ```

#### API changes

* `Systemd.builder()` was removed: there is a single [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance per process, returned by
  [Systemd.instance()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), whose periodic tasks take `Duration`s, and can be enabled, changed or disabled at
  any time:

  ```java
  // 4.x
  Systemd systemd = Systemd.builder().statusUpdate(5, TimeUnit.SECONDS).extendTimeout(5, TimeUnit.SECONDS, TimeUnit.SECONDS.toMicros(29))
      .watchdog(10, TimeUnit.SECONDS).build();
  // 5.0
  Systemd systemd = Systemd.instance();
  systemd.enableStatusUpdate(Duration.ofSeconds(5));
  systemd.enableExtendTimeout(Duration.ofSeconds(5), Duration.ofSeconds(29));
  systemd.enableWatchdog(Duration.ofSeconds(10));
  ```

  To leave the watchdog disabled, leave out the call instead of passing a period of 0. Under Spring Boot, the instance is no longer a bean: use
  `Systemd.instance()` instead of injecting it.
* The building blocks no longer take a `Systemd` instance: create
  [SystemdLifecycle](apidocs/com/github/jpmsilva/jsystemd/spring/SystemdLifecycle.html) with the application context it is a bean of,
  [ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html) without arguments, and
  [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html) with the bean factory alone
  (see [How to use](howto.html#Manual_wiring)).
* Status and health providers are added and removed, instead of set: `setStatusProviders(...)` and `setHealthProvider(...)` were replaced by
  [addStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), [removeStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html),
  [addHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and [removeHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html),
  and `getHealthProvider()` by [getHealthProviders()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).
* `PendingHealthProvider` was removed: extend [DelayableHealthProvider](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html), implementing
  [evaluateHealth()](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html), and call
  [setUnhealthyDelay(Duration.ofSeconds(60))](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html) instead of
  `new PendingHealthProvider(provider, 60, ChronoUnit.SECONDS)`.
* [HealthProvider.Health](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html) is an immutable record: read it with
  [healthy()](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html) and [details()](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html),
  and use [Health.HEALTHY](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html#HEALTHY) instead of `Health.healthy()`.
* [SystemdNotify](apidocs/com/github/jpmsilva/jsystemd/SystemdNotify.html) can no longer be instantiated, and `SystemdServletContextListener` was removed.
* The Spring Boot classes moved out of the `com.github.jpmsilva.jsystemd` package: the building blocks, such as the status and health providers and
  [ConditionalOnSystemd](apidocs/com/github/jpmsilva/jsystemd/spring/ConditionalOnSystemd.html), to `com.github.jpmsilva.jsystemd.spring`, and the
  autoconfiguration to `com.github.jpmsilva.jsystemd.spring.autoconfigure`. Update the imports, and the exclusions of
  [SystemdAutoConfiguration](apidocs/com/github/jpmsilva/jsystemd/spring/autoconfigure/SystemdAutoConfiguration.html) by name, if any.
* The status provider interface, the built-in status providers, and some of their beans, were renamed:

  | 4.x                                       | 5.0                                |
  |-------------------------------------------|------------------------------------|
  | `SystemdStatusProvider`                   | [StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html)                   |
  | `SystemdHeapStatusProvider`               | [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)               |
  | `SystemdNonHeapStatusProvider`            | [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)            |
  | `SystemdClassLoaderStatusProvider`        | [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)        |
  | `SystemdApplicationRunStatusProvider`     | [ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html)     |
  | `SystemdApplicationContextStatusProvider` | [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html) |
  | `SystemdTomcatStatusProvider`             | [TomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TomcatStatusProvider.html)             |
  | `SystemdActuatorHealthProvider`           | [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html)     |
  | `SystemdHealthProviderProperties`         | [SystemdProperties](apidocs/com/github/jpmsilva/jsystemd/spring/autoconfigure/SystemdProperties.html), which has all the properties of the starter |

  | 4.x bean                         | 5.0 bean                              |
  |----------------------------------|---------------------------------------|
  | `systemdNotifyHeapStatus`        | `systemdHeapStatusProvider`           |
  | `systemdNotifyNonHeapStatus`     | `systemdNonHeapStatusProvider`        |
  | `systemdNotifyClassLoaderStatus` | `systemdClassLoaderStatusProvider`    |
  | `systemdActuatorHealthProvider`  | `systemdActuatorHealthStatusProvider` |

* The built-in status providers are no longer annotated with `@Order`: the starter orders them 1000 apart (see
  [Status providers](status-providers.html#Custom_status_providers_under_Spring_Boot)), so check the `@Order` of custom status providers placed between
  them.
* The constructor of [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html) takes a
  `HealthContributors`, such as the registry, instead of a `List<HealthIndicator>`.

### Other changes

* New Keycloak provider, `jsystemd-keycloak` (see [Keycloak](keycloak.html)).
* The Spring Boot building blocks moved to a new module, `jsystemd-spring-boot`: depend on it instead of the starter to use them without the
  autoconfiguration (see [How to use](howto.html#Manual_wiring)).
* Status providers can be disabled and configured individually (see [Status providers](status-providers.html)).
* Deadlocked threads can be detected, and reported to the watchdog (see [Health providers](health-providers.html)).
* Under Java 25, the JNA dependency is no longer needed on 64-bit architectures, and can be excluded (see [How to use](howto.html)).
* [Systemd.close()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) tells systemd that the application is stopping, if not told before. Under Spring
  Boot, the instance is closed with the application context.
* New [Systemd.isUnderSystemd()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and
  [Systemd.watchdogTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).
* Watchdog heartbeats are only sent between [ready()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and
  [stopping()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), when systemd enforces the watchdog.
* The timeout can also be extended while the application stops (see [How to](howto.html)).
* Notify sockets in the abstract namespace (`@...`) and `vsock:` addresses are supported.
* A failing status or health provider no longer fails the application: it is left out of the status, or makes the application unhealthy.
* Under Spring Boot, only closing the application context tells systemd that the application is stopping: stopping or pausing it, as for a CRaC
  checkpoint, no longer does.
* The periodic tasks run on their own threads, `jsystemd-status`, `jsystemd-watchdog` and `jsystemd-extend-timeout`, instead of a single `jsystemd-N`.
* The startup status no longer includes an application identifier.
* Under Spring Boot, systemd can be notified that the application is ready before its runners run, with `systemd.ready-event=application-started`
  (see [How to](howto.html)).
* Under Spring Cloud, only the actual application talks to systemd (see [Application hierarchies](application-hierarchies.html)).
