# Keycloak

The `jsystemd-keycloak` provider integrates Keycloak with systemd. It is a single jar, which includes `jsystemd-core`: copy it to the `providers`
directory of Keycloak, and build Keycloak again:

```
cp jsystemd-keycloak-${jsystemd.version}.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
```

The provider then:

* tells systemd that Keycloak is ready once it can serve requests, that is, once it has completed its bootstrap (migrating the database, importing
  realms, creating the initial admin user) and its HTTP server listens;
* extends the startup timeout while the bootstrap makes progress, such as on each realm it imports, or, once opted into, on each changeset of the
  database migration, so that a startup that takes long, as after an upgrade, is not timed out, while one that hangs still is (see
  [Startup progress](#Startup_progress));
* tells systemd that Keycloak is stopping as soon as it is told to stop;
* sends the watchdog heartbeats when the service unit sets `WatchdogSec`, which can take the health of Keycloak into account, once opted into (see
  [Health](#Health));
* sends the status shown by `systemctl status`: `Startup: bootstrapping` while Keycloak bootstraps, or the step of the bootstrap it is at, such as
  `Startup: importing realm acme`, then what it waits for to be ready, such as
  `Startup: waiting for the readiness health checks (down: …)`, with the names of the checks that are down, and `Shutdown: stopping` while it stops,
  followed by the status of Keycloak and of the JVM (see [Status providers](#Status_providers)). A startup that does not complete, which systemd times
  out, shows what Keycloak was doing, or still waiting for.

## When Keycloak is ready

Keycloak completes its bootstrap either before or after its HTTP server starts listening: when its health endpoints are enabled (`health-enabled`), it
starts listening first, so that the readiness endpoint can tell that it is still bootstrapping, and bootstraps afterwards; otherwise, it bootstraps first.
The provider tells systemd that Keycloak is ready once both have happened, in either order.

The provider learns that the bootstrap is ending from the
[PostMigrationEvent](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/models/utils/PostMigrationEvent.html) that Keycloak publishes to its
providers, right before it considers its bootstrap completed. Then:

* when the health endpoints are enabled, the provider waits until the readiness health checks of Keycloak, those of its `/health/ready` endpoint, are all
  up, asking them directly rather than through the endpoint. Keycloak is then ready for systemd exactly when it is for a load balancer: once it has
  completed its bootstrap, and what its readiness health checks check answers, such as its database;
* otherwise, the provider waits until the HTTP server listens, which Keycloak only starts once it has completed its bootstrap.

The startup log tells which one the provider does, with `Telling systemd that Keycloak is ready once its readiness health checks are all up`, or
`once it has completed its bootstrap and its HTTP server listens`.

### When the bootstrap fails

When the bootstrap fails, as when the database cannot be reached, Keycloak logs why, and exits with status 1. The failure shows in the journal of the
service unit, which has Keycloak's error message, and in its result, which `systemctl status` shows, such as
`Main process exited, code=exited, status=1/FAILURE`. Unlike under Spring Boot (see [Startup and shutdown timeouts](timeouts.html)), the status is not
replaced by the reason of the failure, and keeps the last one sent, such as `Shutdown: stopping`.

Keycloak does not tell its providers that its bootstrap failed, nor why: it passes the exception to an error handler of its own, and exits. The reason
could only be had from parts of Keycloak that are not meant for providers, which the provider does not rely on by default (see
[Following the database migration](#Following_the_database_migration)), for a reason that the journal already has.

## Startup progress

While Keycloak bootstraps, the provider follows the steps of the bootstrap that Keycloak lets a provider observe:

* the database migration, which Keycloak runs with Liquibase the first time it starts, and after an upgrade, once opted into (see
  [Following the database migration](#Following_the_database_migration)): each changeset is shown as it is applied, along with the changesets applied
  so far, such as `Startup: migrating the database (changeset 26.2.0-36750, 117 applied)`;
* the realms that Keycloak imports as it starts (`--import-realm`), such as `Startup: importing realm acme`;
* anything else that Keycloak publishes an event for to its providers, such as each realm and each client it creates, as when it creates the master
  realm, or imports one, which is not shown, but tells that the bootstrap makes progress.

Each of these steps extends the startup timeout of the service unit (see [Startup and shutdown timeouts](timeouts.html)): each changeset and each realm
as they start, and the smaller steps, such as each change of a changeset, which can make hundreds, at most once a second. As long as the bootstrap makes
progress, the startup is then not timed out, however long it takes in total, while a startup that hangs still is. A single step that takes longer than
both `TimeoutStartSec` and 29 seconds, such as a changeset that creates an index on a table with millions of rows, still times out.

The rest of the startup does not extend the timeout, as the provider cannot tell whether it makes progress, so `TimeoutStartSec` must still allow
for it:

* the startup of Keycloak before it initializes its providers;
* the database migration, unless opted into following it;
* the wait for the lock of the database, while another node of the cluster migrates it;
* the steps of the bootstrap that publish no event, such as the migration of the realms to a new version of Keycloak, which follows the one of the
  database, and the startup of the caches, in which the node joins its cluster;
* the wait for the HTTP server and for the readiness health checks, once Keycloak has completed its bootstrap.

To stop extending the startup timeout, while still showing the steps of the bootstrap, set `--spi-systemd--default--startup-timeout-extension=false`.

### Following the database migration

The database migration is not followed by default. Keycloak publishes no event while it migrates its database, and only lets its Liquibase connection
provider see the Liquibase instances it migrates it with. Following it means taking the place of that provider, whose SPI (`connectionsLiquibase`) is
internal to Keycloak: it is not meant for providers, and may change in any release of Keycloak, without notice. As the provider of jsystemd uses no
extension point of Keycloak that is not supported unless opted into, the database migration is then neither shown nor extends the startup timeout, so
set `TimeoutStartSec=` long enough for the longest migration expected, such as the one of an upgrade of Keycloak.

To follow it, opt into the Liquibase connection provider of jsystemd, which is a build option, so build Keycloak again:

```
/opt/keycloak/bin/kc.sh build --spi-connections-liquibase--jsystemd--enabled=true
```

The provider, `jsystemd`, then takes the place of the one of Keycloak, and delegates everything to it, only adding a listener to the Liquibase instances
it creates. When Keycloak does not run under systemd, the provider of Keycloak is used as is. Keycloak warns about the internal SPI every time it is
built with it:

```
WARN  [org.keycloak.services] (build-…) KC-SERVICES0047: jsystemd (com.github.jpmsilva.jsystemd.keycloak.LiquibaseProgressProviderFactory) is implementing the internal SPI connectionsLiquibase. This SPI is internal and may change without notice
```

It works on a best effort basis, and is only tested with the release of Keycloak this version of jsystemd is built against (see
[Supported versions](#Supported_versions)). It is written to stay out of the way of the migration should a later release of Keycloak change the SPI:
it delegates to the provider of Keycloak whichever methods it has, through a dynamic proxy, and when it cannot listen to a Liquibase instance, it logs a
warning, and the migration still runs, without being followed. What it cannot guard against is a release of Keycloak that changes the SPI so that the
provider can no longer be loaded, or no longer finds the provider of Keycloak to delegate to: Keycloak would then fail to build, or to migrate its
database, and so to start. Before upgrading Keycloak with the provider opted into, try the upgrade outside of production first, and, should it fail,
build Keycloak again without the option.

## Options

The options are set as for any other provider, on the command line, in `conf/keycloak.conf`, or as environment variables:

| Option                                                 | Default                                                                                                            | Description                                                                                                             |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `--spi-systemd--default--status-update-period-seconds` | `5`                                                                                                                | How often the status is sent to systemd, in seconds, at least 1.                                                        |
| `--spi-systemd--default--watchdog-enabled`             | `true`                                                                                                             | Whether the watchdog heartbeats are sent, when the service unit sets `WatchdogSec`, at half that interval.              |
| `--spi-systemd--default--status-providers`             | `cluster,heap,old-generation,cgroup-memory,datasource,threads,file-descriptors,non-heap,buffer-pools,class-loader` | The status providers shown, separated by commas, which are always shown in the order of the table below.                |
| `--spi-systemd--default--startup-timeout-extension`    | `true`                                                                                                             | Whether the startup timeout is extended while the bootstrap makes progress (see [Startup progress](#Startup_progress)). |

The health conditions that the watchdog can take into account have options of their own, all disabled by default (see [Health](#Health)).

An option whose value is not valid, such as a period that is not a number, is ignored, with a warning in the log, and its default is used instead,
rather than failing the startup of Keycloak.

## Status providers

The status providers are those of Keycloak itself, and the JVM status providers of the core library, with the same keys and in the same order as under
Spring Boot. They are always shown in the order of the table below, by how useful their information is when operating Keycloak, whatever the order of
the option:

| Key                  | Shows                                                                                                                              | Shown by default | Sample                                                              |
|----------------------|------------------------------------------------------------------------------------------------------------------------------------|------------------|---------------------------------------------------------------------|
| `cluster`            | The nodes of the cluster of Keycloak, as this node sees it, and its health when not healthy (see [cluster](#cluster))              | Yes              | `Cluster: 3 nodes`                                                  |
| `heap`               | [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)                                                 | Yes              | `Heap: 139.5 MiB/256.0 MiB`                                         |
| `old-generation`     | [OldGenerationStatusProvider](apidocs/com/github/jpmsilva/jsystemd/OldGenerationStatusProvider.html)                               | Yes              | `Old gen after GC: 120.0 MiB/512.0 MiB`                             |
| `cgroup-memory`      | [CgroupMemoryStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupMemoryStatusProvider.html)                                 | Yes              | `Cgroup memory: 700.0 MiB/1.0 GiB`                                  |
| `cgroup-pressure`    | [CgroupPressureStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupPressureStatusProvider.html)                             | No               | `Cgroup pressure: 2.1% cpu; 0.0% memory; 12.3% io; 40.0% throttled` |
| `datasource`         | The connection pool of the datasource of Keycloak, when Keycloak is built with its metrics enabled (see [datasource](#datasource)) | Yes              | `Datasource: 7/100 connections, 3 waiting`                          |
| `threads`            | [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                                             | Yes              | `Threads: 31 (9 runnable; 14 waiting; 8 timed waiting)`             |
| `virtual-threads`    | [VirtualThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/VirtualThreadStatusProvider.html), on Java 24 and later         | No               | `Virtual threads: 8 mounted; 340 queued; 8 carrier threads`         |
| `file-descriptors`   | [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)                             | Yes              | `File descriptors: 812/65536`                                       |
| `cgroup-tasks`       | [CgroupTasksStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupTasksStatusProvider.html)                                   | No               | `Cgroup tasks: 312/4915`                                            |
| `cpu`                | [CpuStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CpuStatusProvider.html)                                                   | No               | `CPU: 12.5% process; 40.1% system; load 1.20`                       |
| `garbage-collection` | [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html)                       | No               | `GC: 12 pauses, 0.4% of time`                                       |
| `non-heap`           | [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)                                           | Yes              | `Non-heap: 62.7 MiB/64.1 MiB`                                       |
| `buffer-pools`       | [BufferPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/BufferPoolStatusProvider.html)                                     | Yes              | `Buffers: 64.0 MiB/1.0 GiB direct (412); 2.0 GiB mapped (3)`        |
| `class-loader`       | [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)                                   | Yes              | `Classes: 7915`                                                     |
| `version`            | The version of Keycloak that runs                                                                                                  | No               | `Keycloak 26.8.2`                                                   |

The option replaces the default list, so it names every status provider to show, unlike under Spring Boot, where each status provider is enabled or
disabled on its own, on top of the defaults. For example, to also show the garbage collection pauses:

```
--spi-systemd--default--status-providers=cluster,heap,old-generation,cgroup-memory,datasource,threads,file-descriptors,garbage-collection,non-heap,buffer-pools,class-loader
```

An unknown key is ignored, with a warning in the log. The `threads`, `file-descriptors`, `cgroup-tasks`, `garbage-collection` and `datasource` status
providers can also report the health of Keycloak to the watchdog, each with options of its own (see [Health](#Health)). See
[Built-in status providers](status-providers.html#Built-in_status_providers) for more information on those of the JVM.

### cluster

The `cluster` status provider shows the nodes of the cluster of Keycloak, as this node sees it, itself included, such as `Cluster: 3 nodes`: a node that
sees fewer nodes than there are is split from the others, and no longer shares its sessions with them. When the cluster is not healthy, its health follows,
as Infinispan reports it: `Cluster: 3 nodes, rebalancing` while the caches are being redistributed among the nodes, as when a node joins or leaves, or
`Cluster: 2 nodes, degraded` while a cache is degraded, as it may be once the cluster splits.

The cluster is the one of the embedded Infinispan caches of Keycloak, which its nodes form with JGroups, as Keycloak does by default when it runs in
production mode (`kc.sh start`), in which a single node shows `Cluster: 1 node`. Nothing is shown when there is no such cluster: when the caches are
local (`cache=local`, as in development mode, with `kc.sh start-dev`), or when they are kept in an external Infinispan instead. Nothing is shown
either until Keycloak has completed its bootstrap, during which it creates its caches.

### datasource

The `datasource` status provider shows the connection pool of the datasource of Keycloak, the one of its database: the connections in use out of the
most the pool opens (`db-pool-max-size`, 100 by default), and the threads waiting for a connection, such as `Datasource: 7/100 connections, 3 waiting`.

The pool only counts its connections when Keycloak is built with its metrics enabled, which also exposes them on the metrics endpoint of its management
interface:

```
/opt/keycloak/bin/kc.sh build --metrics-enabled=true
```

Nothing is shown otherwise, rather than a pool that would always seem idle, nor until Keycloak has completed its bootstrap. The datasources of other
persistence units, should Keycloak have any, are not shown.

## Health

The watchdog heartbeats are sent while Keycloak runs, when the service unit sets `WatchdogSec`, and only show that Keycloak is alive, unless some health
condition is opted into: while any of them holds, the heartbeat is suppressed, so that systemd restarts Keycloak once `WatchdogSec` elapses, and its
details are logged (see [Health providers](health-providers.html)). As restarts may surprise, they are all disabled by default:

| Option                                                               | Default | Description                                                                                                               |
|----------------------------------------------------------------------|---------|---------------------------------------------------------------------------------------------------------------------------|
| `--spi-systemd--default--threads-deadlock-detection`                 | `false` | Detect deadlocked threads, and report them in the status, such as `2 deadlocked`.                                         |
| `--spi-systemd--default--threads-deadlock-health`                    | `false` | Suppress the watchdog heartbeat while any thread is deadlocked. Requires `threads-deadlock-detection`.                    |
| `--spi-systemd--default--file-descriptors-health`                    | `false` | Suppress the watchdog heartbeat while the open file descriptors are at or above the threshold.                            |
| `--spi-systemd--default--file-descriptors-threshold`                 | `0.9`   | Share of the limit of file descriptors (`LimitNOFILE`) from which Keycloak is unhealthy, greater than 0 and at most 1.    |
| `--spi-systemd--default--file-descriptors-unhealthy-delay-seconds`   | `0`     | How long the file descriptors must stay at or above the threshold before the heartbeat is suppressed, in seconds.         |
| `--spi-systemd--default--cgroup-tasks-health`                        | `false` | Suppress the watchdog heartbeat while the tasks of the control group are at or above the threshold.                       |
| `--spi-systemd--default--cgroup-tasks-threshold`                     | `0.9`   | Share of the limit of tasks (`TasksMax`) from which Keycloak is unhealthy, greater than 0 and at most 1.                  |
| `--spi-systemd--default--cgroup-tasks-unhealthy-delay-seconds`       | `0`     | How long the tasks must stay at or above the threshold before the heartbeat is suppressed, in seconds.                    |
| `--spi-systemd--default--garbage-collection-health`                  | `false` | Suppress the watchdog heartbeat while the garbage collection pauses take the threshold of the time or more.               |
| `--spi-systemd--default--garbage-collection-threshold`               | `0.5`   | Share of the time taken by the garbage collection pauses from which Keycloak is unhealthy, greater than 0 and at most 1.  |
| `--spi-systemd--default--garbage-collection-unhealthy-delay-seconds` | `60`    | How long the pauses must take that share of the time before the heartbeat is suppressed, in seconds.                      |
| `--spi-systemd--default--datasource-health`                          | `false` | Suppress the watchdog heartbeat while the connection pool of the datasource is starved. Requires the metrics of Keycloak. |
| `--spi-systemd--default--datasource-unhealthy-delay-seconds`         | `60`    | How long the connection pool must stay starved before the heartbeat is suppressed, in seconds.                            |
| `--spi-systemd--default--readiness-health`                           | `false` | Suppress the watchdog heartbeat while any readiness health check is down. Requires the health endpoints of Keycloak.      |
| `--spi-systemd--default--readiness-unhealthy-delay-seconds`          | `300`   | How long the readiness health checks must stay down before the heartbeat is suppressed, in seconds.                       |

A delay of `0` suppresses the heartbeat as soon as the health condition holds. Each health condition is taken into account whether or not its status
provider is shown in the status.

### Deadlocks

Keycloak is unhealthy while any of its threads is deadlocked, and the names of the deadlocked threads are logged. As a deadlock never resolves by
itself, the unhealthy state is not delayed, and the watchdog is triggered right away, rather than once `WatchdogSec` elapses (see
[Unrecoverable states](health-providers.html#Unrecoverable_states)). Deadlock health requires detecting deadlocks, which also shows them in the
status: with `threads-deadlock-health` alone, it is ignored, with a warning in the log. To restart Keycloak on deadlocks:

```
--spi-systemd--default--threads-deadlock-detection=true
--spi-systemd--default--threads-deadlock-health=true
```

### File descriptors

Keycloak is unhealthy while its open file descriptors are at or above the threshold, a share of the most it can open, the `LimitNOFILE` of the service
unit: once they run out, it can no longer accept connections. As a burst of connections can also open many file descriptors for a short while, the
unhealthy state can be delayed.

### Tasks

Keycloak is unhealthy while the tasks of the service, its processes and their threads, are at or above the threshold, a share of the most it can have,
the `TasksMax` of the service unit: once they run out, the JVM can no longer create threads, which often leaves Keycloak running, but unable to work.
Without a limit, Keycloak is always healthy. As a burst of work can also create many threads for a short while, the unhealthy state can be delayed.

### Garbage collection

Keycloak is unhealthy while the garbage collection pauses take the threshold of the time or more, since the health was last evaluated. A Keycloak that is
about to run out of heap can spend minutes, or hours, collecting garbage back to back before it fails with an `OutOfMemoryError`, if it ever does: it
is up, but hardly answers. As a burst of allocations can also keep the garbage collector busy for a while, the unhealthy state is delayed by a minute
by default. Only pauses count: under a concurrent collector, such as ZGC or Shenandoah, a Keycloak that runs out of heap stalls on its allocations
rather than pausing, which is not detected.

### Starved datasource

Keycloak is unhealthy while the connection pool of its datasource is starved, with all its connections in use and threads waiting for one, which usually
means that connections leak, or are all held by requests that hang, and only a restart recovers from it. As a burst of requests can also starve the pool
for a while, the unhealthy state is delayed by a minute by default: the pool must stay starved, every time the health is evaluated, for the whole delay.

A database that cannot be reached does not starve the pool, as it cannot open the connections, which are then not in use. As for the
[status provider](#datasource), this requires Keycloak to be built with its metrics enabled (`metrics-enabled`): otherwise, the pool does not count its
connections, Keycloak is always healthy, and a warning in the log says so.

### Readiness health checks

Keycloak is unhealthy while any of its readiness health checks is down, those of its `/health/ready` endpoint, which the provider already waits for
before it tells systemd that Keycloak is ready (see [When Keycloak is ready](#When_Keycloak_is_ready)), and the names of the checks that are down are
logged. This requires the health endpoints of Keycloak (`health-enabled`): without them, Keycloak has no readiness health checks, it is always healthy,
and a warning in the log says so.

The readiness health checks tell whether Keycloak can serve requests, for a load balancer to stop sending it some, which is not the same as whether a
restart would help: they mostly depend on what a restart of Keycloak does not fix, such as its database, when Keycloak is built with its metrics
enabled, and an outage of the database makes every node unhealthy at once. On the other hand, a node that does not recover once the outage is over, or
that is split from its cluster for good, is only fixed by a restart. The unhealthy state is therefore delayed, by five minutes by default: set
`readiness-unhealthy-delay-seconds` longer than the outages Keycloak is expected to ride out, such as a failover of the database, so that only a node
that stays unhealthy beyond them is restarted.

The checks are run every five seconds, on a thread of their own, rather than before each heartbeat: they may wait for what they check for longer than
`WatchdogSec` allows between two heartbeats. Keycloak is also unhealthy while they do not complete within 30 seconds.

## Service unit

The service unit uses `Type=notify`, as for any other application (see [How to](howto.html)), and must also allow notifications from any process of
the service, with `NotifyAccess=all`: `kc.sh` runs Java as a child process, rather than replacing itself with it, so that the notifications do not come
from the main process of the service, which systemd ignores by default. Without it, the startup times out, as systemd never hears that Keycloak is
ready.

```
[Service]
Type=notify
NotifyAccess=all
ExecStart=/opt/keycloak/bin/kc.sh start --optimized
SuccessExitStatus=143
TimeoutStartSec=300
WatchdogSec=30
```

Set `TimeoutStartSec=` long enough for the parts of the startup that do not extend it (see [Startup progress](#Startup_progress)), such as the whole
database migration, unless following it is opted into, and for the slowest single step expected of the others, such as the longest changeset of a
database migration, once it is.

The provider does not include the JNA library that `jsystemd-core` needs on Java 17 to 21, as Keycloak already provides it: a second copy would conflict
with it. When Keycloak runs on Java 22 or later, such as Java 25, jsystemd notifies systemd without JNA (see [Native library](native-library.html)).

Native access, which jsystemd needs on Java 22 and later, is already enabled by `kc.sh`, which passes `--enable-native-access=ALL-UNNAMED` to the JVM
along with its default `JAVA_ADD_OPENS`, so the JVM does not warn about it. If you set `JAVA_ADD_OPENS` yourself, which replaces those defaults, include
`--enable-native-access=ALL-UNNAMED` in it.

## Supported versions

The provider is built and tested against Keycloak 26.8.2, the latest release when this version of jsystemd was released: the integration tests run
it under systemd, with the health endpoints and the metrics enabled and disabled. Other releases of Keycloak 26 are likely to work, but they are not
tested: besides the provider API of Keycloak, the provider relies on SmallRye Health and ArC, which Keycloak is built on, and on parts of Keycloak that
are not meant for providers, each of which it does without when it is not as expected: its Agroal datasource, for the connection pool, its
Infinispan cache manager, for the cluster, and, once opted into, its Liquibase connection provider, for the progress of the database migration.
