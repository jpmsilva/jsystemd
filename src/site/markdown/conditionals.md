# Conditionals

_Note: this page is only relevant for Spring Boot applications._

The autoconfiguration class of the [starter module](jsystemd-spring-boot-starter/index.html)
is guarded with [ConditionalOnSystemd](apidocs/com/github/jpmsilva/jsystemd/spring/ConditionalOnSystemd.html).

This conditional matches when all the following hold:

* the application is running under Linux;
* the environment property [NOTIFY_SOCKET](https://www.freedesktop.org/software/systemd/man/sd_notify.html#%24NOTIFY_SOCKET) is set to a usable
  address: either the path of an existing socket file, or an address with no file, such as a socket in the abstract namespace (starting with `@`) or a
  `vsock:` address;
* the notifications to systemd could be set up (see [Native library](native-library.html)): on Java 22 and later, with the Foreign Function and Memory
  API, unless native access is denied; otherwise, as on Java 17 to 21 or for `vsock:` addresses, the native libsystemd library could be loaded.

Should there be a need to provide custom jsystemd beans, such
as custom [StatusProvider](apidocs/com/github/jpmsilva/jsystemd/StatusProvider.html) implementations, it is preferable to
use this conditional on the configuration class providing them, as it is preferable to not waste time building beans that will never be needed or used.

The same goes for configuration classes that use the [Systemd](apidocs/com/github/jpmsilva/jsystemd/Systemd.html) instance of the process directly,
with [Systemd.instance()](apidocs/com/github/jpmsilva/jsystemd/Systemd.html).
