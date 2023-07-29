# Native library

The integration uses [JNA](https://github.com/java-native-access/jna) (`net.java.dev.jna:jna`) to interface
with [libsystemd](https://github.com/systemd/systemd/tree/master/src/libsystemd) natively.

## Running without JNA

It used to be possible to switch the implementation from JNA with an executable call to the
[systemd-notify](https://www.freedesktop.org/software/systemd/man/systemd-notify.html) program that is bundled with systemd.

However, that method is no longer supported.

For the time being, JNA is the only reliable way to interface with systemd via libsystemd, as the JVM does not
yet [implement proper support for Datagram Channels (SOCK_DGRAM) over Unix Domain Sockets (AF_UNIX)](https://bugs.openjdk.org/browse/JDK-8297837). 
