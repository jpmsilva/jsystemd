# Startup timeout

During startup, as status updates are sent to systemd to notify on progress, the timeout is extended.
This means that as long as the application context is making progress in creating beans, the startup will not
timeout. The default timeout of 30 seconds that systemd implements should be sufficient to ensure the
service starts up correctly.

Note, though, that timeout extension has only be implemented starting with version 236 of systemd.
Check your version of systemd using `systemctl --version`.
