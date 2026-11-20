# Status providers

The status of a service is the line that `systemctl status` shows, sent by the application to systemd. A status provider contributes a part of it,
implementing [StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html): the status is made of the parts of all the status providers
registered in the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance, in their order, separated by commas. A status provider with nothing
to report returns an empty string, and is left out. If no status provider is registered at all, no status is sent to systemd.

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Qui 2018-02-15 10:19:28 WET; 1h 24min ago
 Main PID: 11142 (java)
   Status: "Heap: 139.5 MiB/256.0 MiB, Non-heap: 62.7 MiB/64.1 MiB, Classes: 7915, Threads: 31 (9 runnable; 14 waiting; 8 timed waiting)"
   CGroup: /system.slice/myservice.service
           └─11142 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

The core library provides status providers for the JVM, which any application can use. The integrations with [Spring Boot](#Under_Spring_Boot)
and [Keycloak](keycloak.html) register them for the application, along with status providers of their own.

## Core library

### Registering status providers

Register status providers with [addStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) on the
[Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance of the process, returned by
[Systemd.instance()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), and remove them with
[removeStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html). Their parts are shown in the order they were registered, unless added
at a given position.

The status is sent to systemd periodically once enabled with [enableStatusUpdate(period)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), and right
away with [updateStatus()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), such as at each milestone of a long startup:

```java
Systemd systemd = Systemd.instance();
systemd.addStatusProviders(new HeapStatusProvider(), new NonHeapStatusProvider(), new ClassLoaderStatusProvider(),
    new ThreadStatusProvider(false, false));
systemd.enableStatusUpdate(Duration.ofSeconds(5));
```

See [How to](howto.html) for the full lifecycle, with a complete example.

### Built-in status providers

| Class                                                                                                        | Purpose                                                                        | Sample                                                       |
|--------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|--------------------------------------------------------------|
| [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)                           | Provides the heap memory used, out of the committed size (not a limit)         | `Heap: 139.5 MiB/256.0 MiB`                                  |
| [OldGenerationStatusProvider](apidocs/com/github/jpmsilva/jsystemd/OldGenerationStatusProvider.html)         | Provides the old generation of the heap after the last garbage collection      | `Old gen after GC: 120.0 MiB/512.0 MiB`                      |
| [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)                     | Provides the non-heap memory used, out of the committed size                   | `Non-heap: 62.7 MiB/64.1 MiB`                                |
| [BufferPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/BufferPoolStatusProvider.html)               | Provides the memory used by direct and mapped buffers                          | `Buffers: 64.0 MiB/1.0 GiB direct (412); 2.0 GiB mapped (3)` |
| [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) | Provides the garbage collection pauses since the status was last read          | `GC: 12 pauses, 0.4% of time`                                |
| [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)             | Provides information regarding the number of loaded classes                    | `Classes: 7915`                                              |
| [CpuStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CpuStatusProvider.html)                             | Provides the CPU usage of the process and of the system, and the load average  | `CPU: 12.5% process; 40.1% system; load 1.20`                |
| [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                       | Provides the number of threads by state, and of deadlocked threads, if any     | `Threads: 31 (9 runnable; 14 waiting; 8 timed waiting)`      |
| [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)       | Provides the open file descriptors, and their health                           | `File descriptors: 812/65536`                                |
| [CgroupMemoryStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupMemoryStatusProvider.html)           | Provides the memory used by the control group of the process, out of its limit | `Cgroup memory: 700.0 MiB/1.0 GiB`                           |
| [ExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ExecutorStatusProvider.html)                   | Provides the busy threads and the waiting tasks of a thread pool               | `workers: 3/10 threads, 5 queued`                            |
| [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html)                         | Provides what the application says it is doing                                 | `Reindexing 40%`                                             |

Most of them are created without arguments. The others are configured when created:

* the [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) can also detect deadlocked threads, adding their number to
  the status, such as `2 deadlocked`, when created with `new ThreadStatusProvider(true, false)`;
* the [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) shows the open file descriptors out of the
  most the process can open, the `LimitNOFILE` of the service unit, and is created with the share of that limit from which it reports the application
  as unhealthy, such as `new FileDescriptorStatusProvider(false, 0.9)`;
* the [ExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ExecutorStatusProvider.html) is created with the name to show and the
  `ThreadPoolExecutor` to report, or a supplier of it, for a pool created later;
* the [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html) shows nothing until the application sets a phase. When created
  with `new PhaseStatusProvider(true)`, every change of phase is sent to systemd right away, rather than with the next status update.

The [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html) and the
[FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html) can also report the application health, to restart
it on deadlocks or when its file descriptors are about to run out. Registering them as status providers only adds them to the status: see
[Health providers](health-providers.html) to have their health taken into account.

### Reporting what the application is doing

To report what the application is doing without writing a status provider, register a
[PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html) and set its phase:

```java
PhaseStatusProvider phase = new PhaseStatusProvider(true);
Systemd.instance().addStatusProviders(phase);

phase.setPhase("Reindexing");
try {
  // ...
} finally {
  phase.clearPhase();
}
```

### Custom status providers

You can create your own status information by implementing [StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html), and registering
an instance with [addStatusProviders(...)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html):

```java
public class QueueStatusProvider implements StatusProvider {

  private final Queue<?> queue;

  public QueueStatusProvider(Queue<?> queue) {
    this.queue = queue;
  }

  @Override
  public String status() {
    return "Queue: " + queue.size();
  }
}
```

The status is asked for on every status update, from the thread that sends it: keep it quick, and return an empty string when there is nothing to report.
A status provider only reports the status: it must not drive the lifecycle of the application, by calling `ready()`, `stopping()` or `close()` on the
[Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance.

### Reporting only in some states of the application

A status provider is asked for its status in every state of the application: while it starts up, while it runs, and while it stops. To only report in
some of them, a status provider implements the matching interfaces, one or more of:

* [StartingStatusProvider](apidocs/com/github/jpmsilva/jsystemd/StartingStatusProvider.html), until the application is ready (`ready()`);
* [RunningStatusProvider](apidocs/com/github/jpmsilva/jsystemd/RunningStatusProvider.html), once ready, until the application is stopping
  (`stopping()`);
* [StoppingStatusProvider](apidocs/com/github/jpmsilva/jsystemd/StoppingStatusProvider.html), once the application is stopping, including when it stops
  before it is ready.

In the other states, the status provider is not even asked for its status. This suits status providers whose status means nothing in some states, such as
the progress of the startup, or whose sources may not be available, such as the health of a remote service, checked with a connection pool that is closed
once the application stops:

```java
public class QueueStatusProvider implements RunningStatusProvider {
  // ...
}
```

## Under Spring Boot

When using the autoconfiguration class, the built-in status providers are registered for the application, and
[StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html) beans will also be searched in the application context and used to compose an
extended status message that systemd will display when using the `status` verb. The status is sent to systemd once every five seconds, or at the period set
with `systemd.status-update-period`, such as `30s`, to call the status providers less often, such as Actuator health indicators that query remote services.

Out of the box this module will show memory (heap, old generation, non-heap, buffers and the memory of the control group of the service), classloader
information, threads, file descriptors, the availability of the application, application startup sequence state and bean creation progress, the stage of
the shutdown, as well as the application health when using Spring Boot Actuator. The garbage collection pauses and the CPU usage can be shown as well, once
enabled.

Additionally, the status of the libraries the application uses is shown when they are available: the connections of the embedded web server (Tomcat, Jetty
or Reactor Netty), the HikariCP connection pools, the thread pools of the task executors and schedulers, and the progress of the Liquibase database
migrations run on startup.

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Qui 2018-02-15 10:19:28 WET; 1h 24min ago
 Main PID: 11142 (java)
   Status: "health status: healthy, Liveness: correct; readiness: accepting traffic, Heap: 139.5 MiB/256.0 MiB, Old gen after GC: 48.2 MiB/256.0 MiB, Cgroup memory: 310.4 MiB, Threads: 31 (9 runnable; 14 waiting; 8 timed waiting), File descriptors: 64/524288, http-nio-8080: 8/8192 connections, 3/10 threads, Non-heap: 62.7 MiB/64.1 MiB, Buffers: 1.2 MiB/256.0 MiB direct (14), Classes: 7915"
   CGroup: /system.slice/myservice.service
           └─11142 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

### Spring Boot status providers

On top of the ones of the core library, the Spring Boot module provides the following status providers:

| Class                                                                                                                 | Purpose                                                                            | Sample                                                     | Details                                                     |
|-----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|------------------------------------------------------------|-------------------------------------------------------------|
| [ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html)         | Provides information regarding the application startup sequence state              | `State: context prepared`                                  | [Startup progress](startup-progress.html#Under_Spring_Boot) |
| [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html) | Provides information regarding the bean creation status of the application context | `Creating bean 94 of 472`                                  | [Startup progress](startup-progress.html#Under_Spring_Boot) |
| [TomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TomcatStatusProvider.html)                         | Provides information regarding Tomcat's connectors                                 | `http-nio-8080: 8/8192 connections, 3/10 threads`          | [tomcat](#tomcat)                                           |
| [JettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/JettyStatusProvider.html)                           | Provides the connections and threads of the embedded Jetty servers                 | `jetty-8080: 12/1000 connections, 3/10 threads`            | [jetty](#jetty)                                             |
| [NettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/NettyStatusProvider.html)                           | Provides the connections and requests of the embedded Reactor Netty servers        | `netty-8080: 12 connections, 3 requests`                   | [netty](#netty)                                             |
| [HikariStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/HikariStatusProvider.html)                         | Provides the HikariCP connection pools, and their health                           | `dataSource: 7/10 connections, 3 waiting`                  | [hikari](#hikari)                                           |
| [TaskExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TaskExecutorStatusProvider.html)             | Provides the thread pools of the task executors and schedulers                     | `applicationTaskExecutor: 2/8 threads, 5 queued`           | [task-executors](#task-executors)                           |
| [LiquibaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/LiquibaseStatusProvider.html)                   | Provides the progress of the Liquibase database migrations run on startup          | `Liquibase: applying changeset 042-add-index (41 applied)` | [liquibase](#liquibase)                                     |
| [AvailabilityStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/AvailabilityStatusProvider.html)             | Provides the liveness and readiness of the application                             | `Liveness: correct; readiness: accepting traffic`          | [availability](#availability)                               |
| [ShutdownStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ShutdownStatusProvider.html)                     | Provides the stage the application is at while it shuts down                       | `Shutdown: waiting for active requests`                    | [shutdown](#shutdown)                                       |
| [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html)         | Provides the application health, based on Spring Boot Actuator health contributors | `health status: unhealthy (db)`                            | [actuator-health](#actuator-health)                         |

See the [Startup progress](startup-progress.html#Under_Spring_Boot) page for more information
regarding [ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html) and [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html).

### Enabling and disabling status providers

All built-in status providers are enabled by default, except for `garbage-collection` and `cpu`, which are opted into, to keep the status short. Use the
`systemd.status-providers.*.enabled` properties to enable or disable them:

| Property                                       | Status provider                                                                                                       | Details                                                     |
|------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| `systemd.status-providers.shutdown`            | [ShutdownStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ShutdownStatusProvider.html)                     | [shutdown](#shutdown)                                       |
| `systemd.status-providers.heap`                | [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)                                    |                                                             |
| `systemd.status-providers.old-generation`      | [OldGenerationStatusProvider](apidocs/com/github/jpmsilva/jsystemd/OldGenerationStatusProvider.html)                  |                                                             |
| `systemd.status-providers.non-heap`            | [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)                              |                                                             |
| `systemd.status-providers.buffer-pools`        | [BufferPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/BufferPoolStatusProvider.html)                        |                                                             |
| `systemd.status-providers.garbage-collection`  | [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html)          |                                                             |
| `systemd.status-providers.class-loader`        | [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)                      |                                                             |
| `systemd.status-providers.cpu`                 | [CpuStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CpuStatusProvider.html)                                      |                                                             |
| `systemd.status-providers.threads`             | [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                                | [threads](#threads)                                         |
| `systemd.status-providers.file-descriptors`    | [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)                | [file-descriptors](#file-descriptors)                       |
| `systemd.status-providers.cgroup-memory`       | [CgroupMemoryStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupMemoryStatusProvider.html)                    |                                                             |
| `systemd.status-providers.availability`        | [AvailabilityStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/AvailabilityStatusProvider.html)             | [availability](#availability)                               |
| `systemd.status-providers.application-run`     | [ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html)         | [Startup progress](startup-progress.html#Under_Spring_Boot) |
| `systemd.status-providers.application-context` | [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html) | [Startup progress](startup-progress.html#Under_Spring_Boot) |
| `systemd.status-providers.tomcat`              | [TomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TomcatStatusProvider.html)                         | [tomcat](#tomcat)                                           |
| `systemd.status-providers.jetty`               | [JettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/JettyStatusProvider.html)                           | [jetty](#jetty)                                             |
| `systemd.status-providers.netty`               | [NettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/NettyStatusProvider.html)                           | [netty](#netty)                                             |
| `systemd.status-providers.hikari`              | [HikariStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/HikariStatusProvider.html)                         | [hikari](#hikari)                                           |
| `systemd.status-providers.task-executors`      | [TaskExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TaskExecutorStatusProvider.html)             | [task-executors](#task-executors)                           |
| `systemd.status-providers.liquibase`           | [LiquibaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/LiquibaseStatusProvider.html)                   | [liquibase](#liquibase)                                     |
| `systemd.status-providers.actuator-health`     | [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html)         | [actuator-health](#actuator-health)                         |

The status providers of a library, such as `jetty` or `hikari`, are only set up when the library is available. For example, to stop reacting to bean
creation events, and to show the garbage collection pauses:

```properties
systemd.status-providers.application-context.enabled=false
systemd.status-providers.garbage-collection.enabled=true
```

The [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html) is always set up, for the application to report what it is
doing (see [Custom status providers under Spring Boot](#Custom_status_providers_under_Spring_Boot) below): it shows nothing until the application sets a
phase.

### Configuring status providers

Some status providers have settings of their own, next to `enabled`, under the same `systemd.status-providers.<key>`.

#### threads

The `threads` status provider shows the number of threads by state, such as `Threads: 31 (9 runnable; 14 waiting; 8 timed waiting)`. It can also detect
deadlocked threads, once opted into: their number is added to the status, such as `2 deadlocked`. It is also a health provider, which can restart the
application on deadlocks (see [Health providers](health-providers.html)).

| Property                                              | Default | Description                                               |
|-------------------------------------------------------|---------|-----------------------------------------------------------|
| `systemd.status-providers.threads.deadlock-detection` | `false` | Detect deadlocked threads, and report them in the status. |

#### file-descriptors

The `file-descriptors` status provider shows the open file descriptors out of the most the process can open, the `LimitNOFILE` of the service unit, such
as `File descriptors: 812/65536`. It is also a health provider, which can restart the application when the file descriptors are about to run out (see
[Health providers](health-providers.html)).

#### tomcat

The `tomcat` status provider shows the usage of each connector of the embedded Tomcat:

* the open connections, out of the maximum the connector accepts (`server.tomcat.max-connections`, 8192 by default), which includes idle keep-alive
  connections;
* when the requests are handled by a thread pool, the busy threads, out of the threads in the pool.

So, `http-nio-8080: 8/8192 connections, 3/10 threads` means that the `http-nio-8080` connector has 8 open connections, out of the 8192 it accepts, and
that 3 of the 10 threads of its pool are serving requests.

With virtual threads (`spring.threads.virtual.enabled=true`), the requests are not handled by a thread pool, so only the connections are shown, such as
`http-nio-8080: 8/8192 connections`. When the connector accepts unlimited connections (`server.tomcat.max-connections=-1`), Tomcat does not count them,
so only the threads are shown, such as `http-nio-8080: 3/10 threads`.

When the management endpoints use a port of their own (`management.server.port`), the connector of their server is shown as well. The connectors are
read directly from the embedded Tomcat, so the Tomcat MBeans do not need to be enabled (`server.tomcat.mbeanregistry.enabled`).

#### jetty

The `jetty` status provider shows the usage of each embedded Jetty server, named after the ports it listens on, such as `jetty-8080`, or `jetty-8080+8443`
for a server with several connectors:

* the open connections of all its connectors together, out of the maximum the server accepts when limited (`server.jetty.max-connections`, unlimited by
  default);
* when the requests are handled by a thread pool, the threads running requests, out of the threads in the pool. The threads Jetty keeps for itself, to
  accept connections and wait for their data, never run requests, and are left out of both.

So, `jetty-8080: 12/1000 connections, 3/10 threads` means that the server has 12 open connections, out of the 1000 it accepts, and that 3 of the 10
threads of its pool that can run requests are running one. Without a limit of connections, only their number is shown, such as `jetty-8080: 12 connections, 3/10 threads`.
With virtual threads (`spring.threads.virtual.enabled=true`), the requests are not handled by a thread pool, so only the connections are shown.

When the management endpoints use a port of their own (`management.server.port`), their server is shown as well. A server is only shown once it
listens.

#### netty

The `netty` status provider shows the usage of each embedded Reactor Netty server, as used by Spring WebFlux, named after the port it listens on: the
open connections and the requests being handled, such as `netty-8080: 12 connections, 3 requests`. Reactor Netty handles requests on a few event loop
threads, which never block, so there is no thread pool to report on, nor a maximum of connections.

Reactor Netty does not count its connections and requests unless it records metrics with Micrometer, so the status provider counts them itself, by
customizing the servers Spring Boot creates: disabling it also stops counting them. When the management endpoints use a port of their own
(`management.server.port`), their server is shown as well. A server is only shown once it listens.

#### hikari

The `hikari` status provider requires HikariCP, the connection pool Spring Boot uses by default. For each pool, named after its data source bean, it shows
the connections in use out of the most the pool opens, and the threads waiting for a connection, such as `dataSource: 7/10 connections, 3 waiting`. A pool
is only shown once started, which Hikari does on the first connection.

It is also a health provider, which can restart the application while a pool is starved, which usually means that connections leak (see
[Health providers](health-providers.html)).

#### task-executors

The `task-executors` status provider shows the thread pools of the task executors and schedulers of the application, the `ThreadPoolTaskExecutor` and
`ThreadPoolTaskScheduler` beans, such as the ones Spring Boot sets up for `@Async` and `@Scheduled` methods. Each is named after its bean, and shows the
busy threads out of the threads in the pool, and the tasks waiting for a thread, such as `applicationTaskExecutor: 2/8 threads, 5 queued`. For a
scheduler, the tasks waiting are those scheduled to run, including the periodic ones between their runs, such as `taskScheduler: 1/1 threads, 3 scheduled`.

The threads in the pool are those it has created so far, not its maximum: a pool creates its threads as tasks arrive, so an idle one may show
`0/0 threads`. The beans are found once all singleton beans are created, so that lazy ones are not created for the status. With virtual threads
(`spring.threads.virtual.enabled=true`), Spring Boot sets up task executors and schedulers without a thread pool, which are left out.

To show the thread pool of an executor that is not one of these beans, register an
[ExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ExecutorStatusProvider.html) for it (see [Built-in status providers](#Built-in_status_providers)).

#### availability

The `availability` status provider shows the liveness and readiness of the application, as Spring Boot reports them, such as
`Liveness: correct; readiness: accepting traffic`. It is also a health provider, which can restart the application while its liveness state is broken
(see [Health providers](health-providers.html)).

#### liquibase

The `liquibase` status provider requires Liquibase. It shows the progress of the database migrations run on startup by the `SpringLiquibase` beans, such as
the one Spring Boot sets up, as each changeset is applied, such as `Liquibase: applying changeset 042-add-index (41 applied)`. It is cleared once the
migrations complete, and when they fail, the changeset that failed is still shown while the application fails to start.

Each changeset also extends the startup timeout (see [Startup and shutdown timeouts](timeouts.html)), so that a migration with many changesets does not exceed the
`TimeoutStartSec` of the service unit. A single changeset that takes longer than the extension still exceeds it: raise `TimeoutStartSec` for such a
migration.

The changesets are reported through the customizer of the `SpringLiquibase` beans, which keeps the customizer they already have, such as a
`Customizer<Liquibase>` bean of the application. A customizer that sets a `ChangeExecListener` of its own replaces the one of this provider, in which case
only the start of the migrations is shown.

#### actuator-health

The `actuator-health` status provider requires Spring Boot Actuator. It shows the application health, as its health contributors report it, the same as
the health endpoint (`/actuator/health`), with the same names, such as `db`, `diskSpace`, `ping` or `redis`.

It only names the unhealthy health contributors, such as `health status: unhealthy (db, redis)`. Their details are left out, as they often contain
sensitive information (error messages, host names, paths, ...), and the status can be read by any local user, for instance with `systemctl status`,
regardless of how Spring Boot Actuator exposes health details. The details are logged instead, when the watchdog heartbeat is suppressed.

It is also a health provider, which can restart the application while it is unhealthy. The status shows the health the watchdog takes into account, so
the settings of the health provider, such as the health group whose members are taken into account, or the status codes considered as unhealthy, apply to
the status as well (see [Health providers](health-providers.html)).

The health is only shown while the application runs: while it starts up, the health contributors may depend on beans not created yet, and while it stops,
on resources already closed, such as connection pools, whose health checks would fail and log their failures.

#### shutdown

The `shutdown` status provider shows the stage the application is at while it shuts down, so that a slow shutdown can be told apart from a hung one.
It shows nothing until the application starts shutting down, and then follows the shutdown of the application context:

1. `Shutdown: stopping`, once the context starts closing, while its life cycle beans stop, such as message listeners;
2. `Shutdown: waiting for active requests`, while the embedded web server waits for the requests being handled to complete, when it shuts down
   gracefully, as it does by default (unless `server.shutdown=immediate`);
3. `Shutdown: destroying beans`, once all life cycle beans are stopped, while the beans are destroyed, such as connection pools.

Every change of stage is sent to systemd right away. The status of the other status providers keeps being sent while the application shuts down, such
as the connections of the web server, which drop as the requests complete, except for those that only report while the application starts up or runs,
such as the application health (see [Reporting only in some states of the application](#Reporting_only_in_some_states_of_the_application)). See [Startup and shutdown timeouts](timeouts.html) for how long systemd lets
the shutdown take.

#### Effects of disabling each status provider

| Key                   | Effect of disabling                                                                                                                                                                                                 |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `shutdown`            | The stage of the shutdown is no longer part of the status message. Nothing else is affected.                                                                                                                        |
| `heap`                | The heap memory usage is no longer part of the status message. Nothing else is affected.                                                                                                                            |
| `old-generation`      | The old generation after garbage collection is no longer part of the status message. Nothing else is affected.                                                                                                      |
| `non-heap`            | The non-heap memory usage is no longer part of the status message. Nothing else is affected.                                                                                                                        |
| `buffer-pools`        | The direct and mapped buffers are no longer part of the status message. Nothing else is affected.                                                                                                                   |
| `garbage-collection`  | The garbage collection pauses are no longer part of the status message (the default). Nothing else is affected.                                                                                                     |
| `class-loader`        | The loaded class count is no longer part of the status message. Nothing else is affected.                                                                                                                           |
| `cpu`                 | The CPU usage is no longer part of the status message (the default). Nothing else is affected.                                                                                                                      |
| `threads`             | The thread count is no longer part of the status message, **and deadlocks are no longer detected, nor restart the application, even if opted into**.                                                                |
| `file-descriptors`    | The open file descriptors are no longer part of the status message, **and running out of them no longer restarts the application, even if opted into**.                                                            |
| `cgroup-memory`       | The memory of the control group is no longer part of the status message. Nothing else is affected.                                                                                                                  |
| `availability`        | The liveness and readiness of the application are no longer part of the status message, **and a broken liveness state no longer restarts the application, even if opted into**.                                    |
| `tomcat`              | Tomcat's connectors are no longer part of the status message. Nothing else is affected.                                                                                                                             |
| `jetty`               | Jetty's connections and threads are no longer part of the status message. Nothing else is affected.                                                                                                                 |
| `netty`               | Reactor Netty's connections and requests are no longer part of the status message, and they are no longer counted. Nothing else is affected.                                                                        |
| `hikari`              | The connection pools are no longer part of the status message, **and a starved pool no longer restarts the application, even if opted into**.                                                                       |
| `task-executors`      | The thread pools of the task executors and schedulers are no longer part of the status message. Nothing else is affected.                                                                                           |
| `liquibase`           | The progress of the database migrations is no longer shown, **and the startup timeout is no longer extended as changesets are applied**.                                                                            |
| `application-run`     | The startup sequence state is no longer shown, **and the startup timeout is no longer extended when the startup sequence changes state**.                                                                           |
| `application-context` | The bean creation progress is no longer shown, **and the startup timeout is no longer extended while beans are being created**. No notification is sent to systemd when beans are created during startup.           |
| `actuator-health`     | The application health is no longer part of the status message, **and is no longer signalled to the watchdog, even if opted into**.                                                                                 |

The `application-run` and `application-context` providers, along with `liquibase` while migrations run, are the only mechanism this library uses to
extend the startup timeout (see
[Startup and shutdown timeouts](timeouts.html)). Disabling them means a slow startup can exceed the `TimeoutStartSec` of the service unit and be killed by
systemd, even if it is making progress. If you disable them, it is your responsibility to ensure that startup completes within the timeout, by increasing
`TimeoutStartSec` in the unit or by extending the timeout yourself, for instance by calling [Systemd.extendTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) periodically, or
[Systemd.enableExtendTimeout(period, amount)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and [Systemd.disableExtendTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) on the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance.

### Custom status providers under Spring Boot

Custom status providers, written as described in [Custom status providers](#Custom_status_providers), are registered by declaring them as beans in any
configuration class:

```java
@Configuration
@ConditionalOnSystemd
public class MyConfiguration {
  
  @Bean
  MyStatusProvider myStatusProvider() {
    return new MyStatusProvider();
  }
}
```

In the example above, the configuration class does not need to be annotated with [ConditionalOnSystemd](apidocs/com/github/jpmsilva/jsystemd/spring/ConditionalOnSystemd.html), because
these beans are harmless even when not running under systemd. However, this way these extra resources do not have to be built at all.
Check the [Conditionals](conditionals.html) page for more information.

The status shows first the startup status providers ([ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html) and [ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html)), as well
as any other status provider registered directly in the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance before the application
context is refreshed, in the order they were registered. Then come the [StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html) beans of the application context, in the order given by the
`@Order` annotation of their bean methods or classes.

The built-in status provider beans are ordered by how useful their information is when operating a service in production, 1000 apart, so that custom
status providers can be placed between them. The critical ones, which tell whether the application is in trouble, use negative orders, and the others
positive ones; 0 is left unused:

| Order  | Status provider                                                                                               |
|--------|---------------------------------------------------------------------------------------------------------------|
| -10000 | [ShutdownStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ShutdownStatusProvider.html)             |
| -9000  | [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html)                          |
| -8000  | [ActuatorHealthStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ActuatorHealthStatusProvider.html) |
| -7000  | [AvailabilityStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/AvailabilityStatusProvider.html)     |
| -6000  | [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)                            |
| -5000  | [OldGenerationStatusProvider](apidocs/com/github/jpmsilva/jsystemd/OldGenerationStatusProvider.html)          |
| -4000  | [CgroupMemoryStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupMemoryStatusProvider.html)            |
| -3000  | [HikariStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/HikariStatusProvider.html)                 |
| -2000  | [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                        |
| -1000  | [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)        |
| 1000   | [TomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TomcatStatusProvider.html)                 |
| 2000   | [JettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/JettyStatusProvider.html)                   |
| 3000   | [NettyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/NettyStatusProvider.html)                   |
| 4000   | [TaskExecutorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/TaskExecutorStatusProvider.html)     |
| 5000   | [CpuStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CpuStatusProvider.html)                              |
| 6000   | [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html)  |
| 7000   | [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)                      |
| 8000   | [BufferPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/BufferPoolStatusProvider.html)                |
| 9000   | [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)              |

The [LiquibaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/LiquibaseStatusProvider.html) is the exception: as it reports the migrations run while the beans are created, it is registered directly in the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html)
instance as soon as it is created, right after the startup status providers.

To replace a built-in status provider, such as to configure it in a way its properties do not allow, declare a bean of its type: the built-in one is
then left out, so that the status does not show the same information twice. Give it the order of the built-in one, from the table above, to keep its
place in the status:

```java
@Bean
@Order(-2000)
ThreadStatusProvider myThreadStatusProvider() {
  return new ThreadStatusProvider(true, true);
}
```

The [LiquibaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/LiquibaseStatusProvider.html) cannot be replaced this way, as a bean of the
application would not be registered as soon as it is created; disable it instead.

To report what the application is doing without writing a status provider, inject the [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html) bean and set its phase, which is sent to
systemd right away. The bean only exists when running under systemd, so inject it as optional:

```java
@Component
public class Reindexer {

  private final ObjectProvider<PhaseStatusProvider> phase;

  public Reindexer(ObjectProvider<PhaseStatusProvider> phase) {
    this.phase = phase;
  }

  public void reindex() {
    phase.ifAvailable(provider -> provider.setPhase("Reindexing"));
    try {
      // ...
    } finally {
      phase.ifAvailable(PhaseStatusProvider::clearPhase);
    }
  }
}
```

The [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance can still be used directly, such as to send the status right away, with
`Systemd.instance().updateStatus()`.

## Under Keycloak

The [Keycloak provider](keycloak.html) registers the status providers of the JVM from the core library, with the same keys and in the same order as under
Spring Boot, along with the stage of the startup and of the shutdown. See its page for how to choose which ones are shown.
