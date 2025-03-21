# Use Ubuntu image for building for compatibility with macOS arm64 builds
FROM eclipse-temurin:21-jdk-jammy AS BUILD

# Set necessary args and environment variables for building phoenixd
ARG PHOENIXD_BRANCH=v0.5.1
ARG PHOENIXD_COMMIT_HASH=ab9a026432a61d986d83c72df5619014414557be

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y --no-install-recommends bash git \
    && apt clean

# Git pull phoenixd source at specified tag/branch and compile phoenixd
WORKDIR /phoenixd
RUN git clone --recursive --single-branch --branch ${PHOENIXD_BRANCH} -c advice.detachedHead=false \
    https://github.com/ACINQ/phoenixd . \
    && test `git rev-parse HEAD` = ${PHOENIXD_COMMIT_HASH} || exit 1 \
    && ./gradlew jvmDistTar

# JRE image to minimize final image size
FROM eclipse-temurin:21-jre-jammy as FINAL

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y --no-install-recommends bash

# Create a phoenix group and user
RUN addgroup --system phoenix --gid 1000 \
    && adduser --system phoenix --ingroup phoenix --uid 1000 --home /phoenix
USER phoenix

# Unpack the release
WORKDIR /phoenix
COPY --chown=phoenix:phoenix --from=BUILD /phoenixd/build/distributions/phoenixd-*-jvm.tar .
RUN tar --strip-components=1 -xvf phoenixd-*-jvm.tar

# Indicate that the container listens on port 9740
EXPOSE 9740

# Expose default data directory as VOLUME
VOLUME [ "/phoenix" ]

# Run the daemon
ENTRYPOINT ["/phoenix/bin/phoenixd", "--agree-to-terms-of-service", "--http-bind-ip", "0.0.0.0"]
