# Status providers

## Under Spring Boot

When using the autoconfiguration class, [SystemdNotifyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyStatusProvider.html) beans
will also be searched in the application context and used to compose an extended status message that systemd will display
when using the `status` verb.

Out of the box this module will show memory (heap/non-heap), classloader information, application startup sequence state and bean creation progress.

Additionally, if running with the embedded Tomcat container, status regarding Tomcat's connectors will also be displayed.

```
[root@machine ~]# systemctl status myservice.service
● myservice.service - My Spring Boot Service
   Loaded: loaded (/etc/systemd/system/myservice.service; disabled; vendor preset: disabled)
   Active: active (running) since Qui 2018-02-15 10:19:28 WET; 1h 24min ago
 Main PID: 11142 (java)
   Status: "Heap: 139.5 MiB/256 MiB, Non-heap: 62.7 MiB/64.1 MiB, Classes: 7915"
   CGroup: /system.slice/myservice.service
           └─11142 /opt/jdk17/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/myservice/myservice.jar
```

## Regular Java application

After creating the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance to interface with the service supervisor, register
any instance of [SystemdNotifyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyStatusProvider.html)
with [addStatusProviders](apidocs/com/github/jpmsilva/jsystemd/Systemd.html#addStatusProviders-com.github.jpmsilva.jsystemd.SystemdStatusProvider...-).

The following table lists the provided implementations:

| Class                                                                                                                                    | Purpose                                                                            | Sample                        |
|------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|-------------------------------|
| [SystemdNotifyHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyHeapStatusProvider.html)                             | Provides information regarding heap memory status                                  | `Heap: 139.5 MiB/256 MiB`     |
| [SystemdNotifyNonHeapStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyNonHeapStatusProvider.html)                       | Provides information regarding non heap memory status                              | `Non-heap: 62.7 MiB/64.1 MiB` |
| [SystemdNotifyClassLoaderStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyClassLoaderStatusProvider.html)               | Provides information regarding the number of loaded classes                        | `Classes: 7915`               |
| [SystemdNotifyApplicationRunStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyApplicationRunStatusProvider.html)         | Provides information regarding the application startup sequence state              | `State: context prepared`     |
| [SystemdNotifyApplicationContextStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyApplicationContextStatusProvider.html) | Provides information regarding the bean creation status of the application context | `Creating bean 94 of 472`     |
| [SystemdNotifyTomcatStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyTomcatStatusProvider.html)                         | Provides information regarding Tomcat's connectors                                 | `http-nio-8080: 2/10`         |

See the [Startup progress](startup-progress.html) page for more information
regarding `SystemdNotifyApplicationRunStatusProvider` and `SystemdNotifyApplicationContextStatusProvider`.

See the [Tomcat status](tomcat-status.html) page for more information regarding `SystemdNotifyTomcatStatusProvider`.

## Custom status providers

You can create your own status information extending [SystemdNotifyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyStatusProvider.html),
and provide an instance of such custom classes to systemd

* if using your own managed instance of [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html),
by using [addStatusProviders](apidocs/com/github/jpmsilva/jsystemd/Systemd.html#addStatusProviders-com.github.jpmsilva.jsystemd.SystemdStatusProvider...-)
* under Spring Boot, by registering it as a bean in any configuration class:

```java
@Configuration
@ConditionalOnSystemD
public class MyConfiguration {
  
  @Bean
  MyStatusProvider myStatusProvider() {
    return new MyStatusProvider();
  }
}
```

In the example above, the configuration class does not need to be annotated with [ConditionalOnSystemd](apidocs/com/github/jpmsilva/jsystemd/ConditionalOnSystemd.html), because
these beans are harmless even when not running under systemd. However, this was these extra resources do not have to be built at all.
Check the [Conditionals](conditionals.html) page for more information.

Under Spring Boot you can also autowire the current instance of [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and programmatically manage it:

```java
@Configuration
@ConditionalOnSystemD
public class MyConfiguration {
  
  @Autowire
  MyConfiguration(Systemd systemd) {
    // Force update current service status
    systemd.updateStatus();
  }
}
```

Out of the box, [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) sends status updates to the supervisor daemon once every five seconds.
