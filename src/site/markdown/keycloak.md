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
* tells systemd that Keycloak is stopping as soon as it is told to stop;
* sends the watchdog heartbeats when the service unit sets `WatchdogSec`;
* sends the status shown by `systemctl status`: `Startup: bootstrapping` while Keycloak bootstraps, then what it waits for to be ready, such as
  `Startup: waiting for the readiness health checks (down: …)`, with the names of the checks that are down, and `Shutdown: stopping` while it stops,
  followed by the status of the JVM, as described in [Status providers](status-providers.html). A startup that does not complete, which systemd times
  out, shows what Keycloak was still waiting for.

## When Keycloak is ready

Keycloak completes its bootstrap either before or after its HTTP server starts listening: when its health endpoints are enabled (`health-enabled`), it
starts listening first, so that the readiness endpoint can tell that it is still bootstrapping, and bootstraps afterwards; otherwise, it bootstraps first.
The provider tells systemd that Keycloak is ready once both have happened, in either order.

The provider learns that the bootstrap is ending from the
[PostMigrationEvent](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/models/utils/PostMigrationEvent.html) that Keycloak publishes to its
providers, right before it considers its bootstrap completed. Then:

* when the health endpoints are enabled, the provider waits until the readiness health checks of Keycloak, those of its `/health/ready` endpoint, are all
  up, asking them directly rather than through the endpoint. Keycloak is then ready for systemd exactly when it is for a load balancer: once it has
  completed its bootstrap, and its database answers;
* otherwise, the provider waits until the HTTP server listens, which Keycloak only starts once it has completed its bootstrap.

The startup log tells which one the provider does, with `Telling systemd that Keycloak is ready once its readiness health checks are all up`, or
`once it has completed its bootstrap and its HTTP server listens`.

## Options

The options are set as for any other provider, on the command line, in `conf/keycloak.conf`, or as environment variables:

| Option                                                 | Default                                                                                         | Description                                                                                                |
|--------------------------------------------------------|-------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `--spi-systemd--default--status-update-period-seconds` | `5`                                                                                             | How often the status is sent to systemd, in seconds, at least 1.                                           |
| `--spi-systemd--default--watchdog-enabled`             | `true`                                                                                          | Whether the watchdog heartbeats are sent, when the service unit sets `WatchdogSec`, at half that interval. |
| `--spi-systemd--default--status-providers`             | `heap,old-generation,cgroup-memory,threads,file-descriptors,non-heap,buffer-pools,class-loader` | The status providers shown, separated by commas, which are always shown in the order of the table below.   |

The status providers are the JVM status providers of the core library, with the same keys and in the same order as under Spring Boot, by how useful
their information is in production, whatever the order of the option:

| Key                  | Status provider                                                                                              | Shown by default | Sample                                                       |
|----------------------|--------------------------------------------------------------------------------------------------------------|------------------|--------------------------------------------------------------|
| `heap`               | [HeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/HeapStatusProvider.html)                           | Yes              | `Heap: 139.5 MiB/256.0 MiB`                                  |
| `old-generation`     | [OldGenerationStatusProvider](apidocs/com/github/jpmsilva/jsystemd/OldGenerationStatusProvider.html)         | Yes              | `Old gen after GC: 120.0 MiB/512.0 MiB`                      |
| `cgroup-memory`      | [CgroupMemoryStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CgroupMemoryStatusProvider.html)           | Yes              | `Cgroup memory: 700.0 MiB/1.0 GiB`                           |
| `threads`            | [ThreadStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ThreadStatusProvider.html)                       | Yes              | `Threads: 31 (9 runnable; 14 waiting; 8 timed waiting)`      |
| `file-descriptors`   | [FileDescriptorStatusProvider](apidocs/com/github/jpmsilva/jsystemd/FileDescriptorStatusProvider.html)       | Yes              | `File descriptors: 812/65536`                                |
| `cpu`                | [CpuStatusProvider](apidocs/com/github/jpmsilva/jsystemd/CpuStatusProvider.html)                             | No               | `CPU: 12.5% process; 40.1% system; load 1.20`                |
| `garbage-collection` | [GarbageCollectionStatusProvider](apidocs/com/github/jpmsilva/jsystemd/GarbageCollectionStatusProvider.html) | No               | `GC: 12 pauses, 0.4% of time`                                |
| `non-heap`           | [NonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/NonHeapStatusProvider.html)                     | Yes              | `Non-heap: 62.7 MiB/64.1 MiB`                                |
| `buffer-pools`       | [BufferPoolStatusProvider](apidocs/com/github/jpmsilva/jsystemd/BufferPoolStatusProvider.html)               | Yes              | `Buffers: 64.0 MiB/1.0 GiB direct (412); 2.0 GiB mapped (3)` |
| `class-loader`       | [ClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/ClassLoaderStatusProvider.html)             | Yes              | `Classes: 7915`                                              |

The option replaces the default list, so it names every status provider to show, unlike under Spring Boot, where each status provider is enabled or
disabled on its own, on top of the defaults. For example, to also show the garbage collection pauses:

```
--spi-systemd--default--status-providers=heap,old-generation,cgroup-memory,threads,file-descriptors,garbage-collection,non-heap,buffer-pools,class-loader
```

An unknown key is ignored, with a warning in the log. The status providers have no settings of their own under Keycloak: the `threads` status provider
does not detect deadlocked threads, and neither it nor the `file-descriptors` one is registered as a health provider, so they never affect the watchdog
heartbeat (see [Health providers](health-providers.html)). See [Built-in status providers](status-providers.html#Built-in_status_providers) for more
information on each of them.

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

Set `TimeoutStartSec=` long enough for the slowest startup expected, such as the first one after an upgrade, which migrates the database.

The provider does not include the JNA library that `jsystemd-core` needs on Java 17 to 21, as Keycloak already provides it: a second copy would conflict
with it. When Keycloak runs on Java 22 or later, such as Java 25, jsystemd notifies systemd without JNA (see [Native library](native-library.html)).

Native access, which jsystemd needs on Java 22 and later, is already enabled by `kc.sh`, which passes `--enable-native-access=ALL-UNNAMED` to the JVM
along with its default `JAVA_ADD_OPENS`, so the JVM does not warn about it. If you set `JAVA_ADD_OPENS` yourself, which replaces those defaults, include
`--enable-native-access=ALL-UNNAMED` in it.

## Supported versions

The provider is built and tested against Keycloak 26.8.2, the latest release when this version of jsystemd was released: the integration tests run
it under systemd, with the health endpoints enabled and disabled. Other releases of Keycloak 26 are likely to work, as the provider only relies on the
provider API of Keycloak, and on SmallRye Health and ArC, which Keycloak is built on, but they are not tested.
