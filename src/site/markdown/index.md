# jsystemd

jsystemd aims to provide a better platform to integrate Java applications with systemd, and run them as proper operating system services.

The integration provides bindings for any Java process to notify systemd once it completes initialization, as well
as provide detailed status information to the service supervisor.

The distribution of this software is composed of the following modules:

* the [jsystemd-core](jsystemd-core/index.html) module that includes all the basic bindings to integrate with systemd;
* the [jsystemd-spring-boot](jsystemd-spring-boot/index.html) module that provides the building blocks of jsystemd for Spring Boot applications, such as
status and health providers, without automatic configuration (see [How to use](howto.html#Manual_wiring));
* the [jsystemd-spring-boot-starter](jsystemd-spring-boot-starter/index.html) module that provides automatic configuration of jsystemd inside Spring Boot
applications;
* the [jsystemd-keycloak](jsystemd-keycloak/index.html) provider that does the same for Keycloak (see [Keycloak](keycloak.html)).

**Warning:** As of release 4, the minimum supported JVM version is 17, and the Spring Boot starter requires Spring Boot 4.0.0 or later.

**Warning:** In release 5, the API of the core module and the properties of the Spring Boot starter changed. Check the [Upgrading](upgrading.html) page before upgrading.

The jsystemd-core module packages some components that expose the status of the JVM to systemd (the Spring Boot starter package automatically configures those).

Binaries are available for download from [GitHub](https://github.com/jpmsilva/jsystemd/releases),
and are also published to [Maven Central](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.jpmsilva.jsystemd%22).

Check the [How to](howto.html) page for information on how to get started.
