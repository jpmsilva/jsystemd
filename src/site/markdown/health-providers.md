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

An unhealthy state that the application cannot recover from, such as a deadlock, also triggers the watchdog right away, rather than once `WatchdogSec`
elapses (see [Unrecoverable states](#Unrecoverable_states)).

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

The core library provides the following health providers, which are also status providers. Their health condition is chosen when they are created:

| Class                                                                                                        | Unhealthy while                                                      | Created with                                     |
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|--------------------------------------------------|
| [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                       | any thread is deadlocked                                             | `new ThreadStatusProvider(true, true)`           |
| [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)       | the open file descriptors are at or above the threshold              | `new FileDescriptorStatusProvider(true, 0.9)`    |
| [CgroupTasksStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupTasksStatusProvider.html)             | the tasks of the control group are at or above the threshold         | `new CgroupTasksStatusProvider(true, 0.9)`       |
| [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) | the garbage collection pauses take the threshold of the time or more | `new GarbageCollectionStatusProvider(true, 0.5)` |

* The [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) reports the application as unhealthy while any thread is
  deadlocked, logging the names of the deadlocked threads. As a deadlock never resolves by itself, the state is reported as
  [unrecoverable](#Unrecoverable_states), which triggers the watchdog right away. Deadlock health requires detecting deadlocks:
  `new ThreadStatusProvider(false, true)` throws an `IllegalArgumentException`.
* The [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) reports the application as unhealthy while
  its open file descriptors are at or above the given share of the limit, the `LimitNOFILE` of the service unit, logging both. A threshold that is not
  greater than 0 and at most 1 throws an `IllegalArgumentException`.
* The [CgroupTasksStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupTasksStatusProvider.html) reports the application as unhealthy while the
  tasks of the service, its processes and their threads, are at or above the given share of the limit, the `TasksMax` of the service unit, logging both.
  Once the tasks run out, the JVM can no longer create threads, which often leaves the application running, but unable to work. Without a limit, the
  application is always healthy.
* The [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) reports the application as unhealthy
  while the garbage collection pauses take the given share of the time or more, since the health was last evaluated, logging the pauses and their share.
  An application that is about to run out of heap can spend minutes, or hours, collecting garbage back to back before it fails with an
  `OutOfMemoryError`, if it ever does: it is up, but hardly does any work. As a burst of allocations can also keep the collector busy for a while, set a
  delay (see [Delaying restarts](#Delaying_restarts)). Only pauses count: an application that runs out of heap under a concurrent collector, such as ZGC
  or Shenandoah, stalls on its allocations rather than pausing, which is not detected.

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

### Unrecoverable states

While the application is unhealthy, systemd only restarts it once `WatchdogSec` elapses without a heartbeat, which leaves the application the time to
become healthy again. For an unhealthy state that never resolves by itself, such as a deadlock, that wait only delays the recovery. A health provider
reports such a state as unrecoverable, with
[Health.unrecoverable(...)](apidocs/com/github/jpmsilva/jsystemd/HealthProvider.Health.html):

```java
systemd.addHealthProviders(() -> consumer.isDead()
    ? HealthProvider.Health.unrecoverable(Map.of("queueConsumer", "dead"))
    : HealthProvider.Health.HEALTHY);
```

The heartbeat is suppressed as for any other unhealthy state, and the watchdog is also triggered right away: systemd is told that the application
detected an error it cannot recover from, which it handles as it does a watchdog timeout, without waiting for one. By default, systemd aborts the
application, and then restarts it when the service unit says so, as with `Restart=on-watchdog` or `Restart=on-failure`.

Triggering the watchdog requires systemd 243 or later. Older versions ignore it, and restart the application once `WatchdogSec` elapses, as the heartbeat
is suppressed all the same.

An application that detects such an error by other means than a health provider can trigger the watchdog itself, with
[triggerWatchdog()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) on the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance.
systemd handles it even when the service unit does not set `WatchdogSec`.

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

The [CgroupTasksStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupTasksStatusProvider.html) and the
[GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) support a delay as well: a burst of work can
create many threads, or keep the garbage collector busy, for a short while.

Only health providers whose unhealthy state may resolve by itself should support a delay. The others suppress the heartbeat right away, and report their
state as [unrecoverable](#Unrecoverable_states), so that the watchdog is triggered right away as well: a deadlock, for instance, never resolves by itself,
so delaying the restart would only delay the recovery. The systemd status always shows the actual health, without the delay.

## Under Spring Boot

### Built-in health providers under Spring Boot

The starter registers each of the status providers that can report the health in both roles while it is enabled. Its health condition is still off by
default, so that it always reports the application as healthy, until opted into, each with its own property:

| Status provider      | Health condition: unhealthy while                                                                       | Delay                                          | Opted into with                                                                         | Details                                   |
|----------------------|---------------------------------------------------------------------------------------------------------|------------------------------------------------|-----------------------------------------------------------------------------------------|-------------------------------------------|
| `threads`            | any thread is deadlocked; the names of the deadlocked threads are logged                                | No, and the watchdog is triggered right away   | `systemd.status-providers.threads.deadlock-health=true`, with `deadlock-detection=true` | [threads](#threads)                       |
| `file-descriptors`   | the open file descriptors are at or above the threshold of the limit                                    | Yes                                            | `systemd.status-providers.file-descriptors.health=true`                                 | [file-descriptors](#file-descriptors)     |
| `cgroup-tasks`       | the tasks of the control group are at or above the threshold of the limit                               | Yes                                            | `systemd.status-providers.cgroup-tasks.health=true`, with `enabled=true`                | [cgroup-tasks](#cgroup-tasks)             |
| `garbage-collection` | the garbage collection pauses take the threshold of the time or more                                    | Yes, `1m` by default                           | `systemd.status-providers.garbage-collection.health=true`, with `enabled=true`          | [garbage-collection](#garbage-collection) |
| `hikari`             | any connection pool is starved; the starved data sources are logged                                     | Yes, `1m` by default                           | `systemd.status-providers.hikari.health=true`                                           | [hikari](#hikari)                         |
| `r2dbc`              | any R2DBC connection pool is starved; the starved connection factories are logged                       | Yes, `1m` by default                           | `systemd.status-providers.r2dbc.health=true`, with `enabled=true`                       | [r2dbc](#r2dbc)                           |
| `kafka-listeners`    | any Kafka listener container stopped by itself; the containers are logged                               | Yes                                            | `systemd.status-providers.kafka-listeners.health=true`                                  | [kafka-listeners](#kafka-listeners)       |
| `rabbit-listeners`   | any RabbitMQ listener container stopped by itself; the containers are logged                            | Yes                                            | `systemd.status-providers.rabbit-listeners.health=true`                                 | [rabbit-listeners](#rabbit-listeners)     |
| `jms-listeners`      | any JMS listener container that starts automatically does not run; the containers are logged            | Yes                                            | `systemd.status-providers.jms-listeners.health=true`                                    | [jms-listeners](#jms-listeners)           |
| `availability`       | the liveness state of the application is broken                                                         | Yes; the watchdog is then triggered right away | `systemd.status-providers.availability.health=true`                                     | [availability](#availability)             |
| `actuator-health`    | any health contributor, or any member of a health group, reports an unhealthy status                    | Yes                                            | `systemd.status-providers.actuator-health.health=true`                                  | [actuator-health](#actuator-health)       |
| `tomcat`             | all the threads of a connector are busy, and requests wait for one; the saturated connectors are logged | Yes, `1m` by default                           | `systemd.status-providers.tomcat.health=true`                                           | [tomcat](#tomcat)                         |
| `jetty`              | all the threads of a server are taken, and requests wait for one; the saturated servers are logged      | Yes, `1m` by default                           | `systemd.status-providers.jetty.health=true`                                            | [jetty](#jetty)                           |

The details of each built-in health provider are below. Disabling one of these status providers, with `systemd.status-providers.<key>.enabled=false`, also
removes it from the health providers of the watchdog, even if its health is opted into.

### Configuring health providers

The health of each built-in health provider is configured under `systemd.status-providers.<key>`, along with the other settings of its status provider.

#### threads

The [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) reports the application as unhealthy while any thread is
deadlocked. As a deadlock never resolves by itself, the unhealthy state is not delayed, and is reported as
[unrecoverable](#Unrecoverable_states): the watchdog is triggered right away, rather than once `WatchdogSec` elapses, as delaying the restart would only
delay the recovery.

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

#### r2dbc

The [R2dbcPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/R2dbcPoolStatusProvider.html) requires `r2dbc-pool`, and is only set up once
opted into, with `systemd.status-providers.r2dbc.enabled=true`. It reports the application as unhealthy while a connection pool is starved, with all its
connections in use and subscribers waiting for one, which usually means that connections leak, and only a restart recovers from it. A database that
cannot be reached does not starve the pools, as they cannot open the connections, which are then not in use.

As a burst of requests can also starve a pool for a while, the unhealthy state is delayed by default: a pool must stay starved, every time the health is
evaluated, for the whole delay.

| Property                                         | Default | Description                                                                                                           |
|--------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.r2dbc.health`          | `false` | Suppress the watchdog heartbeat while any connection pool is starved.                                                 |
| `systemd.status-providers.r2dbc.unhealthy-delay` | `1m`    | Delay signalling the unhealthy state to the watchdog: a pool must stay starved for this long. `0` disables the delay. |

#### kafka-listeners

The [KafkaListenerStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/KafkaListenerStatusProvider.html) requires Spring for Apache Kafka. It
reports the application as unhealthy while a Kafka listener container stopped by itself, which leaves the application up, but no longer consuming: after
an error it does not recover from, such as when it is not authorized, unless it is set to retry, when it is fenced, or when its error handler stops it,
as the `CommonContainerStoppingErrorHandler` does. The containers are logged, by the id of their listener, or the name of their bean.

Spring for Apache Kafka tells such a container apart from the others itself, so a container that was never started, such as one of a listener with
`autoStartup = "false"`, or that the application stopped or paused, never makes the application unhealthy. Brokers that cannot be reached do not either,
as the containers keep running, trying to reach them.

A container that stopped by itself can be started again without restarting the application, such as through the `KafkaListenerEndpointRegistry`: delay
the unhealthy state for an application that does so.

| Property                                                   | Default | Description                                                                     |
|------------------------------------------------------------|---------|---------------------------------------------------------------------------------|
| `systemd.status-providers.kafka-listeners.health`          | `false` | Suppress the watchdog heartbeat while any listener container stopped by itself. |
| `systemd.status-providers.kafka-listeners.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `60s`.            |

#### rabbit-listeners

The [RabbitListenerStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/RabbitListenerStatusProvider.html) requires Spring AMQP. It reports the
application as unhealthy while a RabbitMQ listener container stopped by itself, which leaves the application up, but no longer consuming: one of its
consumers failed with a fatal error, such as when its queues are missing (`spring.rabbitmq.listener.simple.missing-queues-fatal`, the default), when it is
not authenticated, or when its listener throws an `Error`, which stops the container, and it has not been started again since. The containers are logged,
by the id of their listener, or the name of their bean.

The containers do not tell why they are stopped, so the fatal errors are learned from the events they publish in the application context
(`ListenerContainerConsumerFailedEvent`). A container that was never started, or that the application stopped, never makes the application unhealthy, nor
does a broker that cannot be reached, as the containers keep running, trying to reach it.

Not every container that stops by itself publishes such an event: a direct container (`spring.rabbitmq.listener.type=direct`) that gives up connecting to
the broker, once its recovery back-off is exhausted, which it never is by default, does not, and does not make the application unhealthy.

| Property                                                    | Default | Description                                                                     |
|-------------------------------------------------------------|---------|---------------------------------------------------------------------------------|
| `systemd.status-providers.rabbit-listeners.health`          | `false` | Suppress the watchdog heartbeat while any listener container stopped by itself. |
| `systemd.status-providers.rabbit-listeners.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `60s`.            |

#### jms-listeners

The [JmsListenerStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/JmsListenerStatusProvider.html) requires the JMS support of Spring. It reports
the application as unhealthy while a JMS listener container that starts automatically does not run, or runs without any consumer left, which leaves the
application up, but no longer consuming. A container ends up in either state by itself once its back-off is exhausted, after failing to connect to the
broker, or to set up its consumers, for as many attempts as it allows. The containers are logged, by the id of their listener, or the name of their bean.

By default, the back-off of a container is never exhausted: it keeps trying to connect to a broker that cannot be reached, every 5 seconds, and is shown as
recovering, rather than stopped. A container only stops by itself with a back-off that limits the attempts, such as one set on its listener container
factory.

**The containers do not tell why they are stopped**, so a container that the application stops itself cannot be told apart from one that stopped by
itself, and makes the application unhealthy too, as does stopping the application context without closing it. Only opt in when the application leaves the
containers that start automatically running, or delay the unhealthy state for longer than it stops them. A container that does not start automatically
(`spring.jms.listener.auto-startup=false`, or as set on its listener container factory) is left for the application to start and stop, and never makes it
unhealthy.

| Property                                                 | Default | Description                                                                                          |
|----------------------------------------------------------|---------|------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.jms-listeners.health`          | `false` | Suppress the watchdog heartbeat while any listener container that starts automatically does not run. |
| `systemd.status-providers.jms-listeners.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `60s`.                                 |

#### cgroup-tasks

The [CgroupTasksStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupTasksStatusProvider.html) is opted into, with
`systemd.status-providers.cgroup-tasks.enabled=true`. It reports the application as unhealthy when the tasks of the service, its processes and their
threads, are about to run out, as the JVM can then no longer create threads, which often leaves the application running, but unable to work. The limit is
the `TasksMax` of the service unit: without one, the application is always healthy. The tasks and their limit are logged.

| Property                                                | Default | Description                                                                                                       |
|---------------------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.cgroup-tasks.health`          | `false` | Suppress the watchdog heartbeat while the tasks of the control group are at or above the threshold.               |
| `systemd.status-providers.cgroup-tasks.threshold`       | `0.9`   | Share of the limit from which the application is unhealthy, greater than 0 and at most 1. Out of range, it fails. |
| `systemd.status-providers.cgroup-tasks.unhealthy-delay` | not set | Delay signalling the unhealthy state to the watchdog, such as `30s`, so that a burst of work is tolerated.        |

#### garbage-collection

The [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) is opted into, with
`systemd.status-providers.garbage-collection.enabled=true`. It reports the application as unhealthy while the garbage collection pauses take the threshold
of the time or more, since the health was last evaluated. An application that is about to run out of heap can spend minutes, or hours, collecting garbage
back to back before it fails with an `OutOfMemoryError`, if it ever does, which `-XX:+ExitOnOutOfMemoryError` only acts on then: it is up, but hardly
does any work. The pauses and the share of the time they took are logged.

As a burst of allocations can also keep the garbage collector busy for a while, the unhealthy state is delayed by default: the pauses must take the
threshold of the time or more, every time the health is evaluated, for the whole delay. Only pauses count: under a concurrent collector, such as ZGC or
Shenandoah, an application that runs out of heap stalls on its allocations rather than pausing, which is not detected.

| Property                                                      | Default | Description                                                                                                                       |
|---------------------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.garbage-collection.health`          | `false` | Suppress the watchdog heartbeat while the garbage collection pauses take the threshold of the time or more.                       |
| `systemd.status-providers.garbage-collection.threshold`       | `0.5`   | Share of the time from which the application is unhealthy, greater than 0 and at most 1. Out of range, it fails.                  |
| `systemd.status-providers.garbage-collection.unhealthy-delay` | `1m`    | Delay signalling the unhealthy state to the watchdog: the pauses must take that share of the time for this long. `0` disables it. |

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

As the application cannot recover from a broken liveness state, it is reported as [unrecoverable](#Unrecoverable_states): the watchdog is triggered right
away, rather than once `WatchdogSec` elapses. An application that may report a broken liveness state and then recover from it sets a delay, and the
watchdog is then only triggered once the liveness state has been broken for the whole delay.

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

#### tomcat

The [TomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TomcatStatusProvider.html) requires the embedded Tomcat. It reports the
application as unhealthy while a connector is saturated: all the threads its pool can have (`server.tomcat.threads.max`, 200 by default) are busy with
requests, and further requests are waiting for one. A connector that stays saturated usually means that its requests hang, on something that never
answers, such as a remote service called without a timeout or a lock that is never released: the requests never complete, the threads never come back,
and only a restart recovers from it. The saturated connectors are logged.

As a burst of requests can also saturate a connector for a while, the unhealthy state is delayed by default: a connector must stay saturated, every time
the health is evaluated, for the whole delay. An application that is merely overloaded for longer than the delay is restarted as well, which does not
help it: choose a delay longer than the load peaks it is expected to handle, or leave the health disabled.

| Property                                          | Default | Description                                                                                                                  |
|---------------------------------------------------|---------|------------------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.tomcat.health`          | `false` | Suppress the watchdog heartbeat while any connector is saturated.                                                            |
| `systemd.status-providers.tomcat.unhealthy-delay` | `1m`    | Delay signalling the unhealthy state to the watchdog: a connector must stay saturated for this long. `0` disables the delay. |

The connector of the management endpoints, when they use a port of their own (`management.server.port`), is taken into account as well. With virtual
threads (`spring.threads.virtual.enabled=true`), the requests are not handled by a thread pool, so there are no threads to run out of, and a connector
is never saturated: the health then has no effect.

#### jetty

The [JettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/JettyStatusProvider.html) requires the embedded Jetty. It reports the
application as unhealthy while a server is saturated, for the same reasons as with [Tomcat](#tomcat): its pool has all the threads it can have
(`server.jetty.threads.max`, 200 by default), none of them is idle, and requests are waiting for one. The saturated servers are logged.

Jetty may keep one of its threads in reserve rather than run a request with it, so a saturated server may still show a thread to spare in the status,
such as `7/8 threads`: what tells that it is saturated is that requests are waiting.

| Property                                         | Default | Description                                                                                                               |
|--------------------------------------------------|---------|---------------------------------------------------------------------------------------------------------------------------|
| `systemd.status-providers.jetty.health`          | `false` | Suppress the watchdog heartbeat while any server is saturated.                                                            |
| `systemd.status-providers.jetty.unhealthy-delay` | `1m`    | Delay signalling the unhealthy state to the watchdog: a server must stay saturated for this long. `0` disables the delay. |

The server of the management endpoints, when they use a port of their own, is taken into account as well, and, as with Tomcat, a server is never
saturated with virtual threads.

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

The [Keycloak provider](keycloak.html) sends the watchdog heartbeats, and registers the health providers whose health condition is opted into, each with
its own option. As under Spring Boot, none is by default, so that the heartbeat only shows that Keycloak is alive:

| Health condition: unhealthy while                                                  | Delay                                        | Opted into with                                              | Details                                                          |
|------------------------------------------------------------------------------------|----------------------------------------------|--------------------------------------------------------------|------------------------------------------------------------------|
| any thread is deadlocked; the names of the deadlocked threads are logged           | No, and the watchdog is triggered right away | `threads-deadlock-health`, with `threads-deadlock-detection` | [Deadlocks](keycloak.html#Deadlocks)                             |
| the open file descriptors are at or above the threshold of the limit               | Yes                                          | `file-descriptors-health`                                    | [File descriptors](keycloak.html#File_descriptors)               |
| the tasks of the control group are at or above the threshold of the limit          | Yes                                          | `cgroup-tasks-health`                                        | [Tasks](keycloak.html#Tasks)                                     |
| the garbage collection pauses take the threshold of the time or more               | Yes, a minute by default                     | `garbage-collection-health`                                  | [Garbage collection](keycloak.html#Garbage_collection)           |
| the connection pool of the datasource is starved                                   | Yes, a minute by default                     | `datasource-health`                                          | [Starved datasource](keycloak.html#Starved_datasource)           |
| any readiness health check of Keycloak is down; the names of the checks are logged | Yes, five minutes by default                 | `readiness-health`                                           | [Readiness health checks](keycloak.html#Readiness_health_checks) |

The options are those of the provider, such as `--spi-systemd--default--datasource-health=true`, and the delays are set in seconds, with the
`unhealthy-delay-seconds` option of each health condition, such as `--spi-systemd--default--datasource-unhealthy-delay-seconds=120`. Unlike under Spring
Boot, a health condition is taken into account whether or not its status provider is shown in the status.
