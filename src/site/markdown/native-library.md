# Native library

systemd receives the notifications of a service as datagrams on a Unix domain socket, whose address it sets in the environment variable
[NOTIFY_SOCKET](https://www.freedesktop.org/software/systemd/man/sd_notify.html#%24NOTIFY_SOCKET). The JDK cannot send datagrams over Unix domain sockets
([JDK-8297837](https://bugs.openjdk.org/browse/JDK-8297837)), so the integration calls native code, in one of two ways, depending on the Java version the
application runs on:

| Java version  | How the notifications are sent                                                                                                                         | Needs                                         |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| 17 to 21      | `sd_notify` from [libsystemd](https://github.com/systemd/systemd/tree/master/src/libsystemd), called through [JNA](https://github.com/java-native-access/jna) | JNA (`net.java.dev.jna:jna`) and libsystemd   |
| 22 and later  | the C library functions that libsystemd itself uses, called through the [Foreign Function and Memory API](https://openjdk.org/jeps/454) of the JDK  | nothing besides the JDK                       |

`jsystemd-core` is a multi-release JAR, so the JVM picks the right way by itself: there is nothing to configure. When jsystemd is repackaged into another
JAR, such as a shaded or uber JAR, that JAR must also declare `Multi-Release: true` in its manifest, or the JVM ignores the classes for Java 22 and later,
and JNA is used on every Java version. Executable JARs built by Spring Boot declare it.

On Java 22 and later, JNA and libsystemd are still used:

* for the addresses that are not Unix domain sockets, that is, the `vsock:` addresses that systemd uses to receive notifications from virtual machines.
  The Unix domain sockets that systemd sets up for its services, either a file system path such as `/run/systemd/notify`, or a name in the abstract
  namespace starting with `@`, need neither;
* as a fallback, when the Foreign Function and Memory API cannot be used, as on JVMs without a native linker for their platform, or on architectures whose
  socket constants jsystemd does not know, such as SPARC.

When running under systemd, [Systemd.logStatus()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), which the Spring Boot starter and the
Keycloak provider call at startup, logs which way is used:

```
Running under systemd, OS name: "Linux", notify socket: "/run/systemd/notify", notifications sent through: Foreign Function and Memory API
```

## Leaving JNA out on Java 22 and later

JNA is a dependency of `jsystemd-core`, so that applications running on Java 17 to 21 work without further ado. Applications that run on Java 22 or
later, such as on the Java 25 LTS, do not need it, unless systemd notifies them through a `vsock:` address, and can leave it out of their dependencies (see
[How to use](howto.html)).

## Enabling native access

On Java 22 and later, calling native code through the Foreign Function and Memory API is a _restricted_ operation
([JEP 472](https://openjdk.org/jeps/472)): the JVM requires the application to allow it, so that the code that may crash the JVM, or corrupt its memory,
is known. When the application does not allow it, the JVM still lets jsystemd call native code, but warns on its standard error once, when the
notifications are set up:

```
WARNING: A restricted method in java.lang.foreign.Linker has been called
WARNING: java.lang.foreign.Linker::downcallHandle has been called by com.github.jpmsilva.jsystemd.NotifySocket in an unnamed module (file:/opt/myservice/lib/jsystemd-core.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

As the last line says, a future Java release will refuse it instead. The JNA path has the same requirement from Java 24 on, as loading a native library
through JNI is restricted as well.

To allow it, pass this option to `java`, as in the `ExecStart` of the service unit:

```
--enable-native-access=ALL-UNNAMED
```

`ALL-UNNAMED` allows it for the code on the class path, where jsystemd usually is. When jsystemd is on the module path instead, name its module: as the
JAR declares none, it is the automatic module name that the JVM derives from the name of the JAR file, such as `jsystemd.core` for
`jsystemd-core-5.0.0.jar`.

For an executable JAR, started with `java -jar`, the same can be declared in the manifest of the JAR, so that every way of starting it allows it:

```
Enable-Native-Access: ALL-UNNAMED
```

Only `ALL-UNNAMED` is supported there, and only in the manifest of the JAR given to `java -jar`. With Maven, for instance:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <configuration>
    <archive>
      <manifestEntries>
        <Enable-Native-Access>ALL-UNNAMED</Enable-Native-Access>
      </manifestEntries>
    </archive>
  </configuration>
</plugin>
```

The Spring Boot Maven plugin keeps this entry in the executable JAR it repackages. For Keycloak, see [Keycloak](keycloak.html).

When native access is denied, with `--illegal-native-access=deny`, neither way can be used, as loading libsystemd through JNA is denied as well: the
notifications cannot be set up, and the integration is disabled, as described below.

## When the notifications cannot be set up

The notifications are only set up when systemd expects them, that is, when running under Linux with `NOTIFY_SOCKET` set. If they cannot be set up, for
instance when libsystemd is not installed on Java 17 to 21, or when native access is denied on Java 22 and later, the integration is disabled, and under
Spring Boot a warning with the cause is logged at startup, once logging is initialized. Without the integration, systemd receives no notifications, so a
service of `Type=notify` will fail to start once its startup timeout expires.

Outside Spring Boot, the same warning is logged by calling [Systemd.logStatus()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).

Once set up, a notification that cannot be sent, as when systemd no longer listens on the socket, or is not allowed to receive it, is not an error for
the application, as with libsystemd. The first of consecutive failures is logged as a warning, though, as systemd may then consider the service failed,
such as when its watchdog expires, and the recovery is logged once notifications are sent again.

## Running without native code

It used to be possible to switch the implementation from JNA with an executable call to the
[systemd-notify](https://www.freedesktop.org/software/systemd/man/systemd-notify.html) program that is bundled with systemd.

However, that method is no longer supported: systemd attributes a notification to the process that sends it, so a notification sent by
`systemd-notify` is only accepted with `NotifyAccess=all`, and a process would be started for each notification, such as each watchdog heartbeat.
