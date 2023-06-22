# Startup progress

_Note: this page is only relevant for Spring Boot applications._

As a Spring Boot application starts up, it progresses through various stages as defined
in [SpringApplicationRunListener](https://docs.spring.io/spring-boot/docs/3.0.0/api/org/springframework/boot/SpringApplicationRunListener.html):
 * starting
 * environment prepared
 * context prepared
 * context loaded
 * started
 * ready
 * failed (not used)

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

This status information will only be shown during the startup sequence of a Spring Boot application, and will no longer be displayed after systemd is notified
that the service is ready.
