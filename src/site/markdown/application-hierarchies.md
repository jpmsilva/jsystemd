# Application hierarchies

_Note: this page is only relevant for Spring Boot applications._

systemd follows a service through a single process, and that process reports a single state: it tells systemd once that it is
[ready](https://www.freedesktop.org/software/systemd/man/sd_notify.html#READY=1), it has one status line, and it notifies one watchdog. The
[starter module](jsystemd-spring-boot-starter/index.html) maps this onto a single Spring Boot application: the one started with
[SpringApplication](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplication.html), whose startup progress is shown in
the status, and whose readiness is reported to systemd.

Spring allows building hierarchies of application contexts, where a child context sees the beans of its parent. Child contexts created by Spring Boot
itself, without a `SpringApplication` of their own, do not change the above. The most common one is the management context of Spring Boot Actuator, created
when the management endpoints are served on their own port (`management.server.port`): the application is reported to systemd as usual, and only the beans of
the child context are left out of the [startup progress](startup-progress.html), which follows the bean creation of the application context.

Hierarchies made of several applications are a different matter, and fall in one of two cases:

* the [bootstrap context of Spring Cloud](#Spring_Cloud_bootstrap_context) is supported by the starter, with a caveat;
* [any other hierarchy](#Other_hierarchies), such as an application built from parent and child applications, is not supported by the starter.

## Spring Cloud bootstrap context

The legacy [bootstrap context](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html) of Spring
Cloud starts a bootstrap application, which loads the configuration (from a config server, for instance), and makes its context the parent of the
application context. It is
[no longer enabled by default since Spring Cloud 2020.0](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2020.0-Release-Notes),
and is enabled by `spring-cloud-starter-bootstrap`, `spring.cloud.bootstrap.enabled=true` or `spring.config.use-legacy-processing=true`.

The starter recognizes the bootstrap application and leaves it alone: only the actual application reports to systemd. The same applies to the applications
that Spring Cloud starts each time the configuration is refreshed, while the bootstrap context is enabled.

However, the bootstrap application runs before the starter sets up the integration with systemd, so **nothing is reported to systemd while it runs**:
neither a status nor an extension of the [startup timeout](timeouts.html). Loading the configuration from a remote system, such as a config server,
is often the slowest part of the startup, and when it takes longer than `TimeoutStartSec`, systemd stops the service, with nothing in its status to say why.

**Prefer importing the configuration with [spring.config.import](https://docs.spring.io/spring-cloud-config/reference/client.html)**, which replaced the
bootstrap context: the configuration is then loaded by the application itself, without a separate bootstrap application. If the bootstrap context cannot be
avoided, account for the time it takes to load the configuration in `TimeoutStartSec`.

## Other hierarchies

Applications can also be built as a hierarchy of several `SpringApplication`s, with
[SpringApplicationBuilder](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/builder/SpringApplicationBuilder.html) (`parent(...)`,
`child(...)` or `sibling(...)`). The parent application is started first, and the child application only once the parent has fully started.

**This is not supported by the starter**, as supporting it would add a large amount of complexity for uncommon cases. The starter sets up the integration
for every application it sees, and cannot be told which one is the service, which leads to the following:

* systemd is told that the service is ready as soon as the parent application has started, before the child application even starts. The service is
  considered active while it is still starting, units ordered after it are started too early, and the startup timeout of systemd no longer covers the child
  application;
* during the startup of the child application, the status alternates between the startup progress of the child application and the status of the parent
  application;
* the status providers are added once for each application, so that the status shows the same information more than once, such as the heap usage.

Use a single application when running under systemd. If the application must be built as a hierarchy, depend on the
[jsystemd-spring-boot module](jsystemd-spring-boot/index.html) instead of the starter. This module has the building blocks of the starter without its
autoconfiguration: nothing is set up automatically, and the application decides itself when to tell systemd that it is ready, what to report in the status,
and when it is stopping, as in the [manual wiring](howto.html#Manual_wiring) example of How to use. In a hierarchy, configure the
[Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance before starting the parent application, tell systemd that the service is ready once
the last child application has started, and close the instance once all the applications are closed.
