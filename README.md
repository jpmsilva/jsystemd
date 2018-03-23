# jsystemd

This project contains modules to integrate Java services with systemd.

It aims primarily to provide decent integration of services developed with Spring Boot, but
may contain interesting and reusable components for other projects.

## How to use

When using Spring Boot, simply import the `com.github.jpmsilva.jsystemd:systemd-spring-boot-starter`
dependency into your own project.

Refer to the [https://jpmsilva.github.io/jsystemd-site/](https://jpmsilva.github.io/jsystemd-site/)
for more information.

### Additional status

When using the auto-configuration class, beans of type `com.github.jpmsilva.jsystemd.SystemdNotify`
will also be searched and used to compose an extended status message that systemd will display
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
```

You can create your own status information by providing instances of `SystemdNotify` to the application context.

Status is updated once every five seconds.

### Startup feedback

As the application starts up, the Spring Boot application progresses through the stages defined
in [SpringApplicationRunListener](https://docs.spring.io/spring-boot/docs/1.5.10.RELEASE/api/org/springframework/boot/SpringApplicationRunListener.html):
 * starting
 * context loaded
 * context prepared
 * environment prepared
 * finished

This status is shown when requesting the service status:
```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: activating (start) since Wed 2018-03-07 23:01:10 WET; 3s ago
 Main PID: 21034 (java)
   Status: "State: context prepared"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk1.8.0/bin/java -XX:+ExitOnOutOfMemoryError -Xms256M -Xmx512M -XX:+UseG1GC -jar /opt/myservice/myservice.jar
```

As soon as the application context is ready and starts creating beans, the status will also show the progress on bean creation, which can give you an
approximate measure of the percent complete.
```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: activating (start) since Wed 2018-03-07 23:01:10 WET; 11s ago
 Main PID: 21034 (java)
   Status: "State: context loaded, Creating bean 94 of 472"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk1.8.0/bin/java -XX:+ExitOnOutOfMemoryError -Xms256M -Xmx512M -XX:+UseG1GC -jar /opt/myservice/myservice.jar
```

This status information will only be shown during the startup sequence of the Spring Boot application, and will not be displayed after systemd is notified
that the unit is ready.

### Startup timeout

During startup, as status updates are sent to systemd to notify on progress, the timeout is extended.
This means that as long as the application context is making progress in creating beans, the startup will not
timeout. The default timeout of 30 seconds that systemd implements should be sufficient to ensure the
service starts up correctly.

### Tomcat information

Additionally, when using the Tomcat starter, the Catalina mbeans will be used to show connector usage on the service status. The connector status corresponds
to the current usage of the thread pool, in the form of <busy threads>/<total available threads>. So, `http-nio-8080: 5/10` means that 5 out of the 10
available threads of the `http-nio-8080` connector are actively serving requests.

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Wed 2018-03-07 23:01:36 WET; 11min ago
 Main PID: 21034 (java)
   Status: "Heap: 339 MiB/512 MiB, Non-heap: 118.1 MiB/121.5 MiB, Classes: 15734, http-nio-8080: 2/10"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk1.8.0/bin/java -XX:+ExitOnOutOfMemoryError -Xms256M -Xmx512M -XX:+UseG1GC -jar /opt/myservice/myservice.jar
```

### Native library

Out of the box, the implementation relies on the presence of the dependency of `net.java.dev.jna:jna` to
interface with `libsystemd` natively.

You can however suppress this dependency, in which case the strategy will switch to calling the `systemd-notify` binary,
by spawning a separate process. This method has it's drawbacks, though, as the service type must now allow
all processes from the same process group to send notification messages, by adding the configuration `NotifyAccess=all` to the `[Service]`
key.

In the absence of `systemd-notify` the implementation library chosen will be `com.github.jpmsilva.jsystemd.SystemdNotifyNoop`, and no integration will
be performed.

### Conditionals

The auto-configuration class is guarded with `com.github.jpmsilva.jsystemd.ConditionalOnSystemd`.
This conditional searches for the presence of the environment property `NOTIFY_SOCKET` (see the
[sd_notify documentation](https://www.freedesktop.org/software/systemd/man/sd_notify.html#%24NOTIFY_SOCKET) for
more details), as well as the operating system being Linux.
