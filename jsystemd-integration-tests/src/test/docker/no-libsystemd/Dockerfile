# A container without systemd nor libsystemd, in which the integration tests run the fixture application as if systemd expected notifications.

# not pinned by digest: the tag already fixes the release, and its updates are mostly security fixes, which the tests are meant to
# run with; the packages below are installed from the live repositories anyway, so a digest would not make the image reproducible.
FROM docker.io/library/alpine:3.23

# the major version of the JDK the application runs on, set by the tests: on Java 22 and later, the notifications are sent without libsystemd
ARG JDK

RUN apk add --no-cache openjdk${JDK}-jre-headless socat

COPY lib /opt/fixture/lib
COPY classes /opt/fixture/classes

# the library only sets up the notifications when the notify socket exists, so socat creates it, and prints whatever is sent to it, one per line
ENV NOTIFY_SOCKET=/run/notify

CMD ["sh", "-c", "socat -u UNIX-RECVFROM:/run/notify,fork SYSTEM:'cat; echo' & while [ ! -S /run/notify ]; do sleep 0.1; done; exec java --enable-native-access=ALL-UNNAMED -cp '/opt/fixture/classes:/opt/fixture/lib/*' com.github.jpmsilva.jsystemd.FixtureApplication"]
