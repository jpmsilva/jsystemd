# jSystemD

This project contains modules to integrate Java services with SystemD.

It aims primarily to provide decent integration of services developed with Spring Boot, but
may contain interesting and reusable components for other projects.

## How to use

When using Spring Boot, simply import the `com.github.jpmsilva.jsystemd:systemd-spring-boot-starter`
dependency into your own project.

Auto-configuration takes place through the class `com.github.jpmsilva.jsystemd.SystemDAutoConfiguration`,
and will notify SystemD once your application starts up successfully, via a event listener for events
of type `org.springframework.boot.context.event.ApplicationReadyEvent`.

When using this library, you service units can now use `Type=notify` under the `[Service]` unit configuration.

### Additional status

When using the auto-configuration class, beans of type `com.github.jpmsilva.jsystemd.SystemDNotify`
will also be searched and used to compose an extended status message, that SystemD will display
when using the `status` verb.

Out of the box this module will show memory and classloader information:

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Qui 2018-02-15 10:19:28 WET; 1h 24min ago
 Main PID: 11142 (java)
   Status: "Heap: 139.5 MiB/256 MiB, Non-heap: 62.7 MiB/64.1 MiB, Classes: 7915"
   CGroup: /system.slice/myservice.service
           └─11142 /opt/jdk1.8.0/bin/java -XX:+ExitOnOutOfMemoryError -Xms256M -Xmx512M -XX:+UseG1GC -jar /opt/myservice/myservice.jar

Fev 15 10:19:17 template java[11142]: -- INFO --- [           main] org.apache.catalina.core.StandardEngine  : Starting Servlet Engine: Apache Tomcat/8.5.27
Fev 15 10:19:23 template java[11142]: -- INFO --- [ost-startStop-1] org.apache.jasper.servlet.TldScanner     : At least one JAR was scanned for TLDs yet contained no TLDs. Enable debug logging for this logger...
Fev 15 10:19:23 template java[11142]: -- INFO --- [ost-startStop-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring embedded WebApplicationContext
Fev 15 10:19:28 template java[11142]: -- INFO --- [           main] c.g.j.jsystemd.SystemDNotifyNative       : Signaling SystemD that service is ready
Fev 15 10:19:28 template systemd[1]: Started My Spring Boot Service.
Fev 15 10:19:28 template java[11142]: -- INFO --- [           main] i.v.c.a.MyServiceApplication             : Started MyServiceApplication in 14.98 seconds (JVM running for 15.863)
```

You can create your own status information by providing instances of SystemDNotify to the application context.

Status is updated once every ten seconds.

### Native library

Out of the box, the implementation relies on the presence of the dependency of `net.java.dev.jna:jna` to
interface with `libsystemd` natively.

You can however suppress this dependency, in which case the strategy will switch to calling the `systemd-notify` binary,
by spawning a separate process. This method has it's drawbacks, though, as the service type must now allow
all processes from the same process group to send notification messages, by adding the configuration `NotifyAccess=all` to the `[Service]`
key.

In the absence of `systemd-notify` you will see a message `Disabling SystemD notifications` in the log, and no integration will
be performed. 

### Conditionals

The auto-configuration class is guarded with `com.github.jpmsilva.jsystemd.ConditionalOnSystemD`.
This conditional searches for the presence of the environment property `NOTIFY_SOCKET` (see the
[sd_notify documentation](https://www.freedesktop.org/software/systemd/man/sd_notify.html#%24NOTIFY_SOCKET) for
more details).
