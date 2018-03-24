# Conditionals

_Note: this page is only relevant for Spring Boot applications._

The auto-configuration class ot the [starter module](jsystemd-spring-boot-starter/index.html)  
is guarded with [ConditionalOnSystemd](apidocs/com/github/jpmsilva/jsystemd/ConditionalOnSystemd.html).

This conditional checks if running under Linux, and searches for the presence of the environment
property [NOTIFY_SOCKET](https://www.freedesktop.org/software/systemd/man/sd_notify.html#%24NOTIFY_SOCKET).

Should there be a need to provide custom jsystemd beans, such
as custom [SystemdNotifyStatusProvider](apidocs/com/github/jpmsilva/jsystemd/SystemdNotifyStatusProvider.html) implementations, it is preferable to
use this conditional on the configuration class providing them, as it preferable to not waste time building beans that will never be needed or used.

If you autowire the current [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) to use it directly, then
make sure to use the provided `@ConditionalOnSystemd`, or a similar `@ConditionalOnBean(Systemd.class)`.
