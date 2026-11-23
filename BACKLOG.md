# Backlog

## Configurable startup timeout extension under Spring Boot

**Context:** the startup timeout is currently only extended as a side effect of the `application-run` and `application-context` status providers,
and of the `liquibase` and `flyway` ones while migrations run (see
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

**Context:** `jsystemd-keycloak` covers the life cycle (ready once bootstrapped and listening, stopping), the progress of the bootstrap, which extends the
startup timeout, the watchdog heartbeats, with the health conditions that are opted into, and the status of Keycloak (cluster, datasource, version) and
of the JVM (see `src/site/markdown/keycloak.md`). `KeycloakIT` tests it with the latest Keycloak release (`org.keycloak.version`), with the health
endpoints enabled and disabled, which changes whether Keycloak bootstraps before or after its HTTP server listens.

**Proposal:**

* Startup progress: the steps of the bootstrap that are not followed yet, and so do not extend the startup timeout. The migration of the realms to a new
  version of Keycloak (`MigrationModelManager`), which follows the one of the database, publishes no event of its own, and has no SPI to observe it
  from: only the events of what it happens to create or update are seen. Neither does the wait for the lock of the database
  (`DBLockProvider.waitForLock`), while another node migrates it, which is not progress of this node anyway.
* Liveness: Keycloak 26.8 has no liveness health checks of its own, only readiness ones, so there is nothing to signal to the watchdog besides the
  readiness health checks, which are opt-in, and delayed by five minutes by default. Revisit if Keycloak adds some.
* Status: the active sessions. With persistent user sessions, the default since Keycloak 26, counting them (`UserSessionProvider`) queries the database
  for each realm, which is too much for a status sent every few seconds: it would need a period of its own, or the session caches only.
* Status: with an external Infinispan, there is no cluster of Keycloak nodes to show, and the `cluster` status provider shows nothing; the connections to
  the external Infinispan could be shown instead (the Infinispan connection provider factory reports their count as operational info).
* Status: the `datasource` status provider only shows the default datasource, not those of additional persistence units, and only when Keycloak is built
  with its metrics enabled, as Agroal does not count its connections otherwise.
* When the health endpoints are disabled, the time between the `PostMigrationEvent`, on which the provider then relies, and the moment Keycloak
  considers its bootstrap completed: it is the time the other providers that observe the event take, a few milliseconds in the tests. It does not show,
  as Keycloak then only listens once it has completed its bootstrap, and the provider waits for it to listen. When the health endpoints are enabled,
  the provider waits for the readiness health checks, which include the bootstrap.

**Origin:** first version of the Keycloak provider.
