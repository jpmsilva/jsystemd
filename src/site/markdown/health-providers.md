# Health providers

The systemd watchdog is enabled by systemd itself, with `WatchdogSec` in the service unit, which sets the `WATCHDOG_USEC` environment variable of the
application. When enabled, a heartbeat must be sent to systemd periodically, and systemd restarts the application if no heartbeat is received within
`WatchdogSec`. The heartbeats are only sent once enabled in the application: with
[enableWatchdog()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) on the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance, which
the Spring Boot starter always calls, and the [Keycloak provider](keycloak.html) unless `--spi-systemd--default--watchdog-enabled` is `false` (see [How to](howto.html)). Heartbeats are only sent
while the application runs: from when it is ready, as systemd enforces `TimeoutStartSec` rather than the watchdog during startup, until it starts
stopping, as systemd then enforces `TimeoutStopSec`. The health providers are only asked while heartbeats are sent.

The heartbeat takes the application health into account, as reported by the health providers, implementing
[HealthProvider](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.html): while any of them reports the application as unhealthy, the heartbeat is
suppressed, so that systemd restarts the application once `WatchdogSec` elapses, and the details of all the unhealthy health providers are logged.
Without health providers, or while all of them report the application as healthy, the heartbeat only shows that the application is alive.

Some [status providers](status-providers.html) can also report the application health. Showing a status and reporting the health are separate roles,
each with its own registration: the watchdog only asks the health providers registered as such, so a status provider that is not registered as a health
provider never affects the heartbeat, whatever it can report.

As restarts may surprise, every built-in health condition is opted into: by default, the application is always healthy.

## Core library

### Registering health providers

Once the watchdog is enabled on the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance, add health providers with
[addHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), and remove them with
[removeHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html). They are asked for the health before every heartbeat, from the thread
that sends it.

```java
Systemd systemd = Systemd.instance();
systemd.enableWatchdog();
systemd.addHealthProviders(new ThreadStatusProvider(true, true));
```

See [How to](howto.html) for the full lifecycle, with a complete example.

### Built-in health providers

The core library provides two health providers, which are also status providers. Their health condition is chosen when they are created:

| Class                                                                                                  | Unhealthy while                                         | Created with                                  |
|--------------------------------------------------------------------------------------------------------|---------------------------------------------------------|-----------------------------------------------|
| [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                 | any thread is deadlocked                                | `new ThreadStatusProvider(true, true)`        |
| [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) | the open file descriptors are at or above the threshold | `new FileDescriptorStatusProvider(true, 0.9)` |

* The [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) reports the application as unhealthy while any thread is
  deadlocked, logging the names of the deadlocked threads. Deadlock health requires detecting deadlocks: `new ThreadStatusProvider(false, true)` throws an
  `IllegalArgumentException`.
* The [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) reports the application as unhealthy while
  its open file descriptors are at or above the given share of the limit, the `LimitNOFILE` of the service unit, logging both. A threshold that is not
  greater than 0 and at most 1 throws an `IllegalArgumentException`.

Adding one of them with [addStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) only adds it to the status: its health is only
taken into account once the same instance is also added with [addHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html):

```java
ThreadStatusProvider threads = new ThreadStatusProvider(true, true);
systemd.addStatusProviders(threads);
systemd.addHealthProviders(threads);
```

### Custom health providers

You can report the health of the application by implementing [HealthProvider](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.html), and
registering an instance with [addHealthProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html). The details of an unhealthy state are logged
when the heartbeat is suppressed:

```java
systemd.addHealthProviders(() -> consumer.isStuck()
    ? new HealthProvider.Health(false, Map.of("queueConsumer", "stuck"))
    : HealthProvider.Health.HEALTHY);
```

A health provider only reports the health: it must not drive the lifecycle of the application, by calling `ready()`, `stopping()` or `close()` on the
[Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance. To have the application restarted, report it as unhealthy.

The health details are logged when the heartbeat is suppressed, but never shown in the systemd status, which can be read by any local user: they may
hold sensitive information, such as error messages or host names.

### Delaying restarts

A health provider whose unhealthy state may resolve by itself, such as one depending on an external system, can delay it, so that short outages do not
restart the application: the heartbeat is then only suppressed once the application has been unhealthy for the whole delay, without interruption.

Such a health provider extends [DelayableHealthProvider](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html), implementing
[evaluateHealth()](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html) instead of [health()](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.html),
and its delay is set with [setUnhealthyDelay(...)](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html). There is no delay until one is set.
The [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) is one, as a burst of connections can open
many file descriptors for a short while:

```java
FileDescriptorStatusProvider fileDescriptors = new FileDescriptorStatusProvider(true, 0.9);
fileDescriptors.setUnhealthyDelay(Duration.ofSeconds(30));
systemd.addHealthProviders(fileDescriptors);
```

Only health providers whose unhealthy state may resolve by itself should support a delay. The others suppress the heartbeat right away: a deadlock, for
instance, never resolves by itself, so delaying the restart would only delay the recovery. The systemd status always shows the actual health, without
the delay.

## Under Spring Boot

### Built-in health providers under Spring Boot

The starter registers each of the status providers that can report the health in both roles while it is enabled. Its health condition is still off by
default, so that it always reports the application as healthy, until opted into, each with its own property:

| Status provider    | Health condition: unhealthy while                                                    | Delay                | Opted into with                                                                         | Details                               |
|--------------------|--------------------------------------------------------------------------------------|----------------------|-----------------------------------------------------------------------------------------|---------------------------------------|
| `threads`          | any thread is deadlocked; the names of the deadlocked threads are logged             | No                   | `systemd.status-providers.threads.deadlock-health=true`, with `deadlock-detection=true` | [threads](#threads)                   |
| `file-descriptors` | the open file descriptors are at or above the threshold of the limit                 | Yes                  | `systemd.status-providers.file-descriptors.health=true`                                 | [file-descriptors](#file-descriptors) |
| `hikari`           | any connection pool is starved; the starved data sources are logged                  | Yes, `1m` by default | `systemd.status-providers.hikari.health=true`                                           | [hikari](#hikari)                     |
| `availability`     | the liveness state of the application is broken                                      | Yes                  | `systemd.status-providers.availability.health=true`                                     | [availability](#availability)         |
| `actuator-health`  | any health contributor, or any member of a health group, reports an unhealthy status | Yes                  | `systemd.status-providers.actuator-health.health=true`                                  | [actuator-health](#actuator-health)   |

The details of each built-in health provider are below. Disabling one of these status providers, with `systemd.status-providers.<key>.enabled=false`, also
removes it from the health providers of the watchdog, even if its health is opted into.

### Configuring health providers

The health of each built-in health provider is configured under `systemd.status-providers.<key>`, along with the other settings of its status provider.

#### threads

The [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) reports the application as unhealthy while any thread is
deadlocked. As a deadlock never resolves by itself, the unhealthy state is not delayed: delaying the restart would only delay the recovery.

| Property                                              | Default | Description                                                                                               |
|-------------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.threads.deadlock-detection` | `false` | Detect deadlocked threads, and report them in the status.                                                 |
| `systemd.status-providers.threads.deadlock-health`    | `false` | Suppress the watchdog heartbeat while any thread is deadlocked, so that systemd restarts the application. |

Deadlock health requires detecting deadlocks: setting `deadlock-health=true` without `deadlock-detection=true` is an error, and the application fails
to start. To restart the application on deadlocks:

```properties
systemd.status-providers.threads.deadlock-detection=true
systemd.status-providers.threads.deadlock-health=true
```

#### file-descriptors

The [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) reports the application as unhealthy when its
file descriptors are about to run out, as a leak of file descriptors never resolves by itself: once they run out, the application can no longer accept
connections or open files. The open and maximum file descriptors are logged.

| Property                                                    | Default | Description                                                                                                       |
|-------------------------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.file-descriptors.health`          | `false` | Suppress the watchdog heartbeat while the open file descriptors are at or above the threshold.                    |
| `systemd.status-providers.file-descriptors.threshold`       | `0.9`   | Share of the limit from which the application is unhealthy, greater than 0 and at most 1. Out of range, it fails. |
| `systemd.status-providers.file-descriptors.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `30s`, so that a burst of connections is tolerated. |

#### hikari

The [HikariStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/HikariStatusProvider.html) requires HikariCP. It reports the application as unhealthy while
a connection pool is starved, with all its connections in use and threads waiting for one, which usually means that connections leak, and only a restart
recovers from it. A database that cannot be reached does not starve the pools, as they cannot open the connections, which are then not in use.

As a burst of requests can also starve a pool for a while, the unhealthy state is delayed by default: a pool must stay starved, every time the health is
evaluated, for the whole delay.

| Property                                          | Default | Description                                                                                                           |
|---------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.hikari.health`          | `false` | Suppress the watchdog heartbeat while any connection pool is starved.                                                 |
| `systemd.status-providers.hikari.unhealthy-delay` | `1m`    | Delay signalling the unhealthy state to the watchdog: a pool must stay starved for this long. `0` disables the delay. |

#### availability

The [AvailabilityStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/AvailabilityStatusProvider.html) reports the application as unhealthy while its
liveness state is broken. Spring Boot defines a broken liveness state as one the application cannot recover from, so that restarting it is the way out,
which is what the systemd watchdog does: this is the same signal as the liveness probe of Kubernetes. Spring Boot reports the application as live once it
starts, and the application reports that it is broken itself, when it detects such a state:

```java
AvailabilityChangeEvent.publish(applicationEventPublisher, this, LivenessState.BROKEN);
```

Unlike the health indicators of Spring Boot Actuator, the liveness state does not depend on external systems, so an outage of a database or a message
broker does not make the application unhealthy. Refusing traffic, the readiness state, does not either.

| Property                                                | Default | Description                                                                            |
|---------------------------------------------------------|---------|----------------------------------------------------------------------------------------|
| `systemd.status-providers.availability.health`          | `false` | Suppress the watchdog heartbeat while the liveness state of the application is broken. |
| `systemd.status-providers.availability.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `30s`.                   |

#### actuator-health

The [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html) requires Spring Boot Actuator. It reports the
application as unhealthy while any health contributor reports one of the status codes considered as unhealthy. It takes into account the same health
contributors as the health endpoint (`/actuator/health`), with the same names, such as `db`, `diskSpace`, `ping` or `redis`, including the reactive ones
and those registered while the application runs. A composite contributor, such as the one of several data sources, is unhealthy while any of its members
is. The details of the unhealthy health contributors are logged.

| Property                                                          | Default | Description                                                                                                                                  |
|-------------------------------------------------------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.actuator-health.health`                 | `false` | Suppress the watchdog heartbeat while the application is unhealthy, so that systemd restarts it.                                             |
| `systemd.status-providers.actuator-health.unhealthy-status-codes` | `DOWN`  | The health status codes considered as unhealthy.                                                                                             |
| `systemd.status-providers.actuator-health.group`                  | not set | The health group whose members are shown in the status and taken into account for the health signal, instead of all the health contributors. |
| `systemd.status-providers.actuator-health.unhealthy-delay`        | not set | Delay signalling the unhealthy state to the watchdog, such as `60s`.                                                                         |

The health group and the status codes apply to the systemd status as well, which only shows the health the watchdog takes into account (see
[Status providers](status-providers.html)).

##### Health groups

Every health contributor is taken into account by default, including those of external systems such as databases or message brokers: an outage of one of
them makes every instance of the application unhealthy, and systemd restarts all of them, which does not fix the outage. Instead, choose which health
contributors may restart the application, with a [health group](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.groups)
of Spring Boot Actuator: only its members are then taken into account, as the health endpoint does for the group, at `/actuator/health/<group>`.

The `liveness` group, which Spring Boot sets up for the liveness probe of Kubernetes, is a natural choice, as it only holds what restarting the application
fixes, its liveness state by default. Spring Boot sets up the liveness and readiness groups by default, unless disabled with
`management.endpoint.health.probes.enabled=false`:

```properties
systemd.status-providers.actuator-health.health=true
systemd.status-providers.actuator-health.group=liveness
```

A group of its own lets the application choose the health contributors that may restart it, with
`management.endpoint.health.group.<name>.include` and `exclude`. The members of a composite contributor are named after their path, such as `db/primary`:

```properties
management.endpoint.health.group.systemd.include=diskSpace,ping,db/primary
systemd.status-providers.actuator-health.health=true
systemd.status-providers.actuator-health.group=systemd
```

The group must exist: a group that does not, or a group without the health endpoint of Spring Boot Actuator, is an error, and the application fails to
start.

When the Actuator health is signalled but no health status codes are considered as unhealthy
(`systemd.status-providers.actuator-health.unhealthy-status-codes=`), so that the heartbeat is never suppressed, a warning is logged; if you notify the
watchdog by other means, suppress it with `systemd.watchdog-suppress-warning=true`.

### Delaying restarts under Spring Boot

The built-in health providers that support a delay, as shown in the table above, set it with their `unhealthy-delay` property (see
[Delaying restarts](#Delaying_restarts)). For instance, to tolerate outages of up to a minute of the health contributors of Spring Boot Actuator:

```properties
systemd.status-providers.actuator-health.health=true
systemd.status-providers.actuator-health.unhealthy-delay=60s
```

A delay and a health group can be combined: the group chooses what may restart the application, and the delay how long it may be unhealthy before it is
restarted.

### Health providers of the application

Any [HealthProvider](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.html) bean of the application, written as described in
[Custom health providers](#Custom_health_providers), is taken into account as well, after the built-in ones: defining the bean is the opt-in.

```java
@Configuration
@ConditionalOnSystemd
public class MyConfiguration {

  @Bean
  HealthProvider queueConsumerHealthProvider(QueueConsumer consumer) {
    return () -> consumer.isStuck()
        ? new HealthProvider.Health(false, Map.of("queueConsumer", "stuck"))
        : HealthProvider.Health.HEALTHY;
  }
}
```

The beans are added in the order given by the `@Order` annotation of their bean methods or classes. A bean extending
[DelayableHealthProvider](apidocs/com/github/jpmsilva/jsystemd/DelayableHealthProvider.html) supports a delay, as described in
[Delaying restarts](#Delaying_restarts).

## Under Keycloak

The [Keycloak provider](keycloak.html) sends the watchdog heartbeats, but registers no health provider: the heartbeat only shows that Keycloak is alive.
