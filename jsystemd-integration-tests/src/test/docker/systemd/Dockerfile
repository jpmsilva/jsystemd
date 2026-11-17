# A container that boots systemd, in which the integration tests run the fixture application, the Spring Boot sample application, and
# Keycloak as transient services, with systemd-run.

# not pinned by digest: the tag already fixes the release, and its updates are mostly security fixes, which the tests are meant to
# run with; the packages below are installed from the live repositories anyway, so a digest would not make the image reproducible.
FROM docker.io/library/debian:trixie-slim

# the major version of the JDK the applications run on, set by the tests: on Java 22 and later, the notifications are sent without libsystemd
ARG JDK

RUN apt-get update \
    && apt-get install -y --no-install-recommends systemd openjdk-${JDK}-jre-headless curl \
    && rm -rf /var/lib/apt/lists/*

# units that are of no use in a container, and only slow down its boot
RUN systemctl mask getty.target console-getty.service systemd-logind.service systemd-udevd.service systemd-udev-trigger.service \
    systemd-networkd.service systemd-resolved.service systemd-timesyncd.service

# tells systemd it runs in a container; Podman sets it on its own, Docker does not
ENV container=docker

# Keycloak only changes with its version, unlike its provider, which is added with the applications below
COPY keycloak /opt/keycloak
RUN chmod +x /opt/keycloak/bin/*.sh

# the applications change on every build, so they come last, to reuse the layers above
COPY lib /opt/fixture/lib
COPY classes /opt/fixture/classes
COPY sample.jar /opt/sample/sample.jar
COPY keycloak-provider.jar /opt/keycloak/providers/jsystemd-keycloak.jar

# systemd shuts down cleanly on this signal, rather than on the default SIGTERM
STOPSIGNAL SIGRTMIN+3

CMD ["/lib/systemd/systemd"]
