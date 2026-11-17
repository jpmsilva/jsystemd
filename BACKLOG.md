# Backlog

## Configurable startup timeout extension under Spring Boot

**Context:** the startup timeout is currently only extended as a side effect of the `application-run` and `application-context` status providers,
and of the `liquibase` one while migrations run (see
`src/site/markdown/timeouts.md`). Users that disable those providers (`systemd.status-providers.*`) lose the protection, and the only way to restore
it is to call `Systemd.enableExtendTimeout(period, amount)` / `disableExtendTimeout()` programmatically.

**Proposal:**

* Add a `systemd.startup-timeout.*` group of `@ConfigurationProperties` (enabled, period, extension amount), read by `SystemdSpringApplicationRunListener`
  when configuring the `Systemd` instance with `enableExtendTimeout(period, amount)`.
* Keep it disabled by default, and say so in the documentation: unlike the extensions sent by the status providers, which are only sent while the startup
  makes progress, a periodic extension is also sent while the startup hangs (a deadlocked bean, a config server that never answers), which effectively
  disables `TimeoutStartSec`. Enabling it trades the detection of a hung startup for tolerance of slow startup steps.
* Document the properties in `status-providers.md` and `timeouts.md`, and test them like the existing listener tests.

**Origin:** follow-up of https://github.com/jpmsilva/jsystemd/issues/15 (selectively disabling status providers).

## Keycloak provider follow-ups

**Context:** the first version of `jsystemd-keycloak` covers the life cycle (ready once bootstrapped and listening, stopping), the watchdog heartbeats and
the JVM status providers (see `src/site/markdown/keycloak.md`). `KeycloakIT` tests it with the latest Keycloak release (`org.keycloak.version`), with the
health endpoints enabled and disabled, which changes whether Keycloak bootstraps before or after its HTTP server listens.

**Proposal:**

* Health: signal the readiness and liveness of Keycloak to the watchdog, opt-in, and the health settings of the JVM providers (`threads`,
  `file-descriptors`), as in the starter. When the health endpoints are enabled, the readiness health checks that the provider already polls until
  Keycloak is ready (`HealthReadiness`) could keep being polled afterwards, as a health provider.
* Status of Keycloak itself, such as its database connection pool, and the active sessions.
* When the health endpoints are disabled, the time between the `PostMigrationEvent`, on which the provider then relies, and the moment Keycloak
  considers its bootstrap completed: it is the time the other providers that observe the event take, a few milliseconds in the tests. It does not show,
  as Keycloak then only listens once it has completed its bootstrap, and the provider waits for it to listen. When the health endpoints are enabled,
  the provider waits for the readiness health checks, which include the bootstrap.

**Origin:** first version of the Keycloak provider.
