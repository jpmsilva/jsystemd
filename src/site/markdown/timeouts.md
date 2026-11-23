# Startup and shutdown timeouts

systemd gives a service a limited time to start, and a limited time to stop: by default 90 seconds each, which can be changed for a service with
[TimeoutStartSec=](https://www.freedesktop.org/software/systemd/man/systemd.service.html#TimeoutStartSec=) and
[TimeoutStopSec=](https://www.freedesktop.org/software/systemd/man/systemd.service.html#TimeoutStopSec=) in its unit (or both at once with
`TimeoutSec=`), or for all services with `DefaultTimeoutStartSec=` and `DefaultTimeoutStopSec=` in `/etc/systemd/system.conf`.

* A service that has not told systemd that it is ready within `TimeoutStartSec=` is considered failed, and stopped.
* A service that has not exited within `TimeoutStopSec=` of being told to stop, usually with `SIGTERM`, is killed with `SIGKILL`.

## Extending the timeouts

Both timeouts can be [extended](https://www.freedesktop.org/software/systemd/man/sd_notify.html#EXTEND_TIMEOUT_USEC=%E2%80%A6) by the service: an
extension applies to the timeout systemd enforces when it is received, `TimeoutStartSec=` while the service starts and `TimeoutStopSec=` while it stops.
Each extension gives the service another 29 seconds by default, counted from the moment it is sent, to send the next one or to finish. The service is
never stopped or killed before the configured timeout has elapsed, extended or not, so an extension can only make the timeout longer.

The [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance extends the timeout in two ways (see [How to](howto.html)):

* [extendTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) sends a single extension, to be called at each milestone of a long startup or
  shutdown. As each extension is only sent when the application makes progress, a startup or shutdown that hangs still times out.
* [enableExtendTimeout(period, amount)](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) sends an extension periodically. It is also sent while the
  startup or shutdown hangs, such as on a deadlock, so that systemd never times it out: it trades the detection of a hung startup or shutdown for
  tolerance of slow steps. Prefer raising the timeout in the unit, unless no value is long enough.

Nothing is sent while the application runs, between [ready()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) and
[stopping()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), as systemd would then extend `RuntimeMaxSec=` instead.

Note, though, that timeout extension has only been implemented starting with version 236 of systemd.
Check your version of systemd using `systemctl --version`.

## Startup

With the Spring Boot starter, the startup timeout is extended at each step of the startup sequence, at most once a second while beans are being created,
and for each changeset the Liquibase database migrations apply, or each migration the Flyway ones apply, as a single bean runs all of them. This means
that as long as the application context is making progress in creating beans, the startup will not time out, however long it takes in total. A single
step that takes longer than both `TimeoutStartSec=` and 29 seconds, such as a bean whose initialization takes that long, or a single changeset or
migration, still times out.

This behavior is provided by the `application-run`, `application-context`, `liquibase` and `flyway` status providers. If you disable them, the timeout is
no longer extended and it is up to you to ensure the startup completes in time. See [Status providers](status-providers.html) for more information.
Anything that runs before the starter sets up the integration with systemd, such as a Spring Cloud bootstrap application, does not extend it either (see
[Application hierarchies](application-hierarchies.html)).

The [Keycloak provider](keycloak.html) extends the startup timeout on the steps of the bootstrap of Keycloak that it can follow: each changeset of the
database migration, each realm that is imported, and at most once a second on the smaller steps. The rest of the startup does not extend it, so set
`TimeoutStartSec=` long enough for it, and for the slowest single step expected, such as the longest changeset of a migration (see
[Startup progress](keycloak.html#Startup_progress)).

Under Spring Boot, the startup also includes the `ApplicationRunner` and `CommandLineRunner` beans, as the application is only ready once they have
run, and the timeout is not extended while they run. For runners that run for long, or never end, notify systemd before they run, with
`systemd.ready-event=application-started` (see [How to](howto.html)).

The periodic extension, when enabled, stops once the application is ready, and once it starts stopping.

## Shutdown

Every integration tells systemd that the application is stopping, with [stopping()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), as soon as the
shutdown starts: the Spring Boot starter as soon as the application context starts closing, before its web server waits for the active requests, and the
Keycloak provider as soon as Keycloak is told to stop. From then on, systemd only enforces `TimeoutStopSec=`, no longer the watchdog (`WatchdogSec=`), so that the
application can take the time it needs to stop, up to `TimeoutStopSec=`.

None of the integrations extends the shutdown timeout: the shutdown is bounded by the timeouts of the framework itself, so set `TimeoutStopSec=` longer
than those, leaving time for the rest of the shutdown, such as closing the connection pools:

* under Spring Boot, the graceful shutdown of the web server waits for the active requests for up to `spring.lifecycle.timeout-per-shutdown-phase`
  (30 seconds by default), as does the stopping of each phase of the life cycle beans;
* under Keycloak, the shutdown waits for `shutdown-delay` (1 second by default), and then for the active requests and the distributed caches for up
  to `shutdown-timeout` (10 seconds by default).

An application that needs more time than any value of `TimeoutStopSec=` allows can extend it: with
[extendTimeout()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) at each milestone of the shutdown, once it has called
[stopping()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html), or by enabling the periodic extension once the application is ready, in preparation for
the shutdown, or once it is stopping. Enabled while the application runs, the periodic extension only starts extending the timeout once the application
is stopping, and runs until the instance is closed. The periodic extension of the startup does not carry over to the shutdown, where it would keep a hung
shutdown from ever timing out.

While the application stops, `systemctl status` shows the stage of its shutdown, such as `Shutdown: waiting for active requests`, so that a slow shutdown
can be told apart from a hung one (see [Status providers](status-providers.html)).

An application that stops on its own before it is ready, for instance because its startup failed, is still considered starting by systemd until it exits:
`TimeoutStartSec=` keeps applying to its shutdown, not `TimeoutStopSec=`.
