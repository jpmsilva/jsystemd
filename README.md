# jsystemd

[![Maven Central](https://img.shields.io/maven-central/v/com.github.jpmsilva.jsystemd/jsystemd-core)](https://central.sonatype.com/namespace/com.github.jpmsilva.jsystemd)

jsystemd is a small Java library for running Java applications as proper systemd services.

It implements the [sd_notify](https://www.freedesktop.org/software/systemd/man/sd_notify.html) protocol, so your application can:

* tell systemd when it has finished starting up (`Type=notify`) and when it is stopping;
* show its status in `systemctl status`, using the built-in status providers (heap, old generation, non-heap, buffers, garbage collection, class loading,
  CPU, threads, file descriptors, control group memory, thread pools) or your own, and report what the application is doing with a phase;
* send watchdog heartbeats (`WatchdogSec`), and stop sending them while the application is unhealthy, so that systemd restarts it. Health can be reported by
  your own health providers (built-in health providers are included that detect deadlocked threads, and file descriptors about to run out);
* extend the startup and shutdown timeouts (`TimeoutStartSec`, `TimeoutStopSec`) during long startups and shutdowns.

When the application is not running under systemd, nothing is sent, so the same code runs unchanged anywhere else: on a developer machine, in tests, or
on other operating systems.

## Modules

| Module                         | What it is for                                                                                                     |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `jsystemd-core`                | The library itself. Plain Java, no framework required: use it in any Java application.                             |
| `jsystemd-spring-boot`         | The building blocks for Spring Boot applications, such as status and health providers, without autoconfiguration. |
| `jsystemd-spring-boot-starter` | An optional convenience for Spring Boot applications, which wires the building blocks into the application.       |
| `jsystemd-keycloak`            | The same for Keycloak, as a Keycloak provider.                                                                     |

`jsystemd-core` does not depend on Spring, Keycloak, or any other framework. The starter and the provider are thin layers on top of it, and everything
they do can be done with the core alone.

## jsystemd-core

### Dependencies

The core is intentionally lean. Its runtime dependencies are:

| Dependency                                                           | Why                                                                                                                                                                                                                                                                                                                               |
|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [JNA](https://github.com/java-native-access/jna) (`net.java.dev.jna:jna`) | On Java 17 to 21, calls `sd_notify` in `libsystemd`. The JDK cannot send datagrams over Unix domain sockets ([JDK-8297837](https://bugs.openjdk.org/browse/JDK-8297837)), which is how systemd receives notifications, so a native call is needed. On Java 22 and later, the notifications are sent with the Foreign Function and Memory API of the JDK instead, so JNA can be left out, unless systemd uses a `vsock:` address. Only used when running on Linux under systemd (`NOTIFY_SOCKET` is set); see [Native library](https://jpmsilva.github.io/jsystemd-site/versions/latest/native-library.html). |
| [SLF4J API](https://www.slf4j.org/) (`org.slf4j:slf4j-api`)           | Logging, through whichever logging framework your application already uses.                                                                                                                                                                                                                                                     |
| [JSpecify](https://jspecify.dev/) (`org.jspecify:jspecify`)           | Nullness annotations on the public API, for IDEs and static analysis tools. Annotations only: no code runs from it.                                                                                                                                                                                                             |

Besides those, it only needs Java 17 or later, and the `java.management` module of the JDK, for the JVM status providers. The CPU and file descriptor
status providers also need the `jdk.management` module, which the JDKs based on OpenJDK provide.

### Usage

Add the dependency:

```xml
<dependency>
  <groupId>com.github.jpmsilva.jsystemd</groupId>
  <artifactId>jsystemd-core</artifactId>
  <version>${jsystemd.version}</version>
</dependency>
```

A minimal integration notifies systemd once the application is ready, and when it is stopping:

```java
Systemd systemd = Systemd.instance();

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
  systemd.stopping();
  application.stop();
  try {
    systemd.close();
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  }
}));

application.start();
systemd.ready();
```

The instance can also enable periodic status updates, watchdog heartbeats and timeout extensions, and status and health providers can be
registered on it. The [How to](https://jpmsilva.github.io/jsystemd-site/versions/latest/howto.html) page describes the full lifecycle, with a complete example.

The service unit then uses `Type=notify`:

```
[Service]
Type=notify
ExecStart=/opt/jdk17/bin/java -jar /opt/myservice/myservice.jar
SuccessExitStatus=143
```

## jsystemd-spring-boot-starter

For Spring Boot applications, from Spring Boot 4.0.0 on, the starter does all of the above automatically: add it as a dependency, and the application
notifies systemd once it is ready to accept traffic.

```xml
<dependency>
  <groupId>com.github.jpmsilva.jsystemd</groupId>
  <artifactId>jsystemd-spring-boot-starter</artifactId>
  <version>${jsystemd.version}</version>
</dependency>
```

On top of the core, it reports the startup progress of the application context, the availability of the application and the stage of its shutdown,
as well as, when the libraries are used, the status of the embedded web server (Tomcat, Jetty or Reactor Netty), the HikariCP connection pools, the
thread pools of the task executors and schedulers, the progress of the Liquibase migrations, and the Actuator health. Once opted into, it also lets the
systemd watchdog restart the application when its threads deadlock, when its file descriptors are about to run out, when a connection pool is starved,
when its liveness state is broken, or when Actuator reports it, or the members of one of its health groups, as unhealthy.

## jsystemd-spring-boot

The status and health providers that the starter sets up live in `jsystemd-spring-boot`, which has no autoconfiguration. Depend on it instead of the
starter to configure the `Systemd` instance and wire the providers yourself:

```xml
<dependency>
  <groupId>com.github.jpmsilva.jsystemd</groupId>
  <artifactId>jsystemd-spring-boot</artifactId>
  <version>${jsystemd.version}</version>
</dependency>
```

The [How to](https://jpmsilva.github.io/jsystemd-site/versions/latest/howto.html#Manual_wiring) page has an example of the manual wiring.

## jsystemd-keycloak

For Keycloak, the provider tells systemd that Keycloak is ready once it has completed its bootstrap and its HTTP server listens, and that it is stopping
as soon as it is told to stop, sends the watchdog heartbeats, and reports the status of the JVM. It is a single jar, to copy to the `providers` directory
of Keycloak: see [Keycloak](https://jpmsilva.github.io/jsystemd-site/versions/latest/keycloak.html).

## Documentation

Refer to the [documentation site](https://jpmsilva.github.io/jsystemd-site/) for the full documentation, including the
[upgrade notes](https://jpmsilva.github.io/jsystemd-site/versions/latest/upgrading.html).

## Building

The build compiles with JDK 17, the minimum supported version, and runs the tests on each supported JDK, the released LTS versions from 17 on: 17, 21
and 25. It finds them through Maven [toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html), so `~/.m2/toolchains.xml` must
declare a JDK toolchain for each of them, while Maven itself can run on any of them:

```xml
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
    </provides>
    <configuration>
      <jdkHome>/path/to/jdk17</jdkHome>
    </configuration>
  </toolchain>
  <!-- the same for 21 and 25 -->
</toolchains>
```

The classes that `jsystemd-core` uses from Java 22 on, under `src/main/java22`, are compiled with JDK 25, as they need the Foreign Function and Memory
API, and tested by `jsystemd-core-java22-tests` on the multi-release jar, as the JVM only uses them from there.

Then build with `./mvnw verify`. `./do-full-build` checks first that the toolchains are available, and also runs the integration tests, which run the
library under a real systemd in containers, and need Docker or the Podman API socket.

## License

jsystemd is licensed under the [Apache License, Version 2.0](LICENSE).
