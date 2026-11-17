# Startup progress

While a service starts up, `systemctl status` shows it as `activating (start)` until the application tells systemd that it is ready. Reporting the
progress of the startup in the status shows what the application is doing meanwhile, so that a slow startup can be told apart from a hung one, and each
step of the startup can also extend the startup timeout, so that a long startup that keeps making progress does not time out (see
[Startup and shutdown timeouts](timeouts.html)).

## Core library

An application reports the progress of its startup with a [PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html), setting
its phase at each step, and extends the startup timeout at the same time with [extendTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).
Created with `new PhaseStatusProvider(true)`, every change of phase is sent to systemd right away, rather than with the next status update:

```java
Systemd systemd = Systemd.instance();
PhaseStatusProvider startup = new PhaseStatusProvider(true);
systemd.addStatusProviders(startup);

startup.setPhase("Startup: migrating the database");
systemd.extendTimeout();
migrateDatabase();

startup.setPhase("Startup: loading the cache");
systemd.extendTimeout();
loadCache();

startup.clearPhase();
systemd.ready();
```

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: activating (start) since Wed 2018-03-07 23:01:10 WET; 11s ago
 Main PID: 21034 (java)
   Status: "Startup: loading the cache"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

The phase is shown until it is set again or cleared: clear it once the application is ready, so that the status no longer shows a step of the startup.
The timeout is only extended until [ready()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) is called, and a single step that takes longer than the
extension, 29 seconds by default, still times out. See [Status providers](status-providers.html) for more information on the
[PhaseStatusProvider](apidocs/com/github/jpmsilva/jsystemd/PhaseStatusProvider.html), and [How to](howto.html) for the full lifecycle.

## Under Spring Boot

The starter reports the progress of the startup by itself. As a Spring Boot application starts up, it progresses through various stages as defined
in [SpringApplicationRunListener](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplicationRunListener.html):

 * starting
 * environment prepared
 * context prepared
 * context loaded
 * started
 * ready
 * failed (not shown)

These stages compose the startup sequence.

The current stage is shown when requesting the service status:

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: activating (start) since Wed 2018-03-07 23:01:10 WET; 3s ago
 Main PID: 21034 (java)
   Status: "State: context prepared"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

As soon as the application context is ready and starts creating beans, the status will also show the progress on bean creation, which can give you an
approximate measure of the percent complete:

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: activating (start) since Wed 2018-03-07 23:01:10 WET; 11s ago
 Main PID: 21034 (java)
   Status: "State: context loaded, Creating bean 94 of 472"
   CGroup: /system.slice/myservice.service
           └─21034 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

The total only counts the beans created on startup: lazy beans, which are only created once used, are left out, and with
`spring.main.lazy-initialization=true` the bean creation progress is not shown at all.

This status information will only be shown during the startup sequence of a Spring Boot application, and will no longer be displayed after systemd is notified
that the service is ready.

The stages and the bean creation progress are reported by the
[ApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationRunStatusProvider.html) and the
[ApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/spring/ApplicationContextStatusProvider.html), the `application-run` and
`application-context` status providers, which also extend the startup timeout as the startup makes progress, along with the `liquibase` status provider
while the database migrations run. See [Status providers](status-providers.html) for how to disable them, and what is lost when doing so.

## Under Keycloak

The [Keycloak provider](keycloak.html) cannot follow the progress of the startup step by step: it shows `Startup: bootstrapping` until Keycloak is ready,
and does not extend the startup timeout as it makes progress. See its page, and [Startup and shutdown timeouts](timeouts.html), for more information.
