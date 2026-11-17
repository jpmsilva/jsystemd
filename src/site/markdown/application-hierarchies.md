# Application hierarchies

_Note: this page is only relevant for Spring Boot applications._

systemd follows a service through a single process, and that process reports a single state: it tells systemd once that it is
[ready](https://www.freedesktop.org/software/systemd/man/sd_notify.html#READY=1), it has one status line, and it notifies one watchdog. The
[starter module](jsystemd-spring-boot-starter/index.html) maps this onto a single Spring Boot application: the one started with
[SpringApplication](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplication.html), whose startup progress is shown in
the status, and whose readiness is reported to systemd.

Spring allows building hierarchies of application contexts, where a child context sees the beans of its parent. The starter targets the common cases only:

* a single application, including one whose child contexts are created by Spring Boot itself, such as the Actuator management context;
* an application using Spring Cloud, including its legacy bootstrap context.

Any other setup, such as an application built from parent and child applications, is not supported by the starter, as supporting it would add a large amount
of complexity for uncommon cases. Such applications can still integrate with systemd, by using the [core module](jsystemd-core/index.html) directly, as a
[regular Java application](howto.html) would, and deciding themselves when to tell systemd that they are ready, what to report in the status, and when they
are stopping.

## Child contexts created by Spring Boot

Some child contexts are created by Spring Boot itself, without a `SpringApplication` of their own. The most common one is the management context of
Spring Boot Actuator, created when the management endpoints are served on their own port (`management.server.port`).

These contexts need no special care: the application is reported to systemd as usual. The beans of the child context are not included in the
[startup progress](startup-progress.html), which only follows the bean creation of the application context.

## Parent and child applications

Applications can also be built as a hierarchy of several `SpringApplication`s, with
[SpringApplicationBuilder](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/builder/SpringApplicationBuilder.html) (`parent(...)`,
`child(...)` or `sibling(...)`). The parent application is started first, and the child application only once the parent has fully started.

**This is not supported**. The starter treats each application as the whole service, which leads to the following:

* systemd is told that the service is ready as soon as the parent application has started, before the child application even starts. The service is
  considered active while it is still starting, units ordered after it are started too early, and the startup timeout of systemd no longer covers the child
  application;
* during the startup of the child application, the status alternates between the startup progress of the child application and the status of the parent
  application;
* the status providers are added once for each application, so that the status shows the same information more than once, such as the heap usage.

Use a single application when running under systemd. If the application must be built from parent and child applications, do not use the starter: it sets
up the integration for every application it sees, and cannot be told which one is the service. Depend on the
[jsystemd-spring-boot module](jsystemd-spring-boot/index.html) instead, which has the building blocks of the starter without its autoconfiguration, and wire it as described in [How to use](howto.html), under "Regular Java application": for instance, configure the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance before
starting the parent application, tell systemd that the service is ready once the last child application has started, and close the instance once all the
applications are closed.

## Spring Cloud

### Bootstrap context

The legacy [bootstrap context](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html) of Spring
Cloud is a hierarchy built by Spring Cloud. It is
[no longer enabled by default since Spring Cloud 2020.0](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2020.0-Release-Notes),
and is enabled by `spring-cloud-starter-bootstrap`, `spring.cloud.bootstrap.enabled=true` or `spring.config.use-legacy-processing=true`. It starts a
bootstrap application, which loads the configuration (from a config server, for instance), and makes its context the parent of the application context.

The starter recognizes the bootstrap application and leaves it alone: only the actual application reports to systemd, as in an application without the
bootstrap context. The same applies to the applications that Spring Cloud starts each time the configuration is refreshed, while the bootstrap context is
enabled.

However, the bootstrap application runs before the starter sets up the integration with systemd, so **nothing is reported to systemd while it runs**:
neither a status nor an extension of the [startup timeout](timeouts.html). Loading the configuration from a remote system, such as a config server,
is often the slowest part of the startup, and when it takes longer than `TimeoutStartSec`, systemd stops the service, with nothing in its status to say why.

**Prefer importing the configuration with [spring.config.import](https://docs.spring.io/spring-cloud-config/reference/client.html)**, which replaced the
bootstrap context, and has been the default since Spring Cloud 2020.0. The configuration is then loaded by the application itself, without a separate
bootstrap application.

If the bootstrap context cannot be avoided, account for the time it takes to load the configuration in `TimeoutStartSec`.
