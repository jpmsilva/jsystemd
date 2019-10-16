# Native library

Out of the box, the integration uses [JNA](https://github.com/java-native-access/jna) (`net.java.dev.jna:jna`) to interface
with [libsystemd](https://github.com/systemd/systemd/tree/master/src/libsystemd) natively.

## Running without JNA

You can however suppress this dependency, in which case the strategy will switch to calling
the [systemd-notify](https://www.freedesktop.org/software/systemd/man/systemd-notify.html) program that is bundled with systemd.

This method has it's drawbacks, though, as the service type must now allow all processes from the same process group to send notification messages,
by adding the configuration `NotifyAccess=all` to the `[Service]` key of your unit.

There are also some known issues with using `systemd-notify` for units running as regular users (lacking the `CAP_SYS_ADMIN` capability).
This would typically be seen systemd appears to refuse and acknowledge the ready event sent by the Java application. In such cases, you may
need to add the configuration `AmbientCapabilities=CAP_SYS_ADMIN` to the `[Service]` key of your unit.

**The JNA integration is always preferred, as it avoids these issues.**

In the absence or otherwise impossibility to execute [systemd-notify](https://www.freedesktop.org/software/systemd/man/systemd-notify.html) the integration is
disabled altogether.
