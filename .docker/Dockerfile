# Use Ubuntu image for building for compatibility with macOS arm64 builds
FROM eclipse-temurin:21-jdk-jammy AS BUILD

# Set necessary args and environment variables for building phoenixd
ARG PHOENIXD_BRANCH=v0.3.4
ARG PHOENIXD_COMMIT_HASH=1a872b40b92bde3ae340bb8a7869a79d11fe5bff

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
    && ./gradlew distTar

# Alpine image to minimize final image size
FROM eclipse-temurin:21-jre-alpine as FINAL

# Upgrade all packages and install dependencies
RUN apk update \
    && apk upgrade --no-interactive
RUN apk add --update --no-cache bash

# Create a phoenix group and user
RUN addgroup -S phoenix -g 1000 \
    && adduser -S phoenix -G phoenix -u 1000 -h /phoenix
USER phoenix

# Unpack the release
WORKDIR /phoenix
COPY --chown=phoenix:phoenix --from=BUILD /phoenixd/build/distributions/phoenix-*-jvm.tar .
RUN tar --strip-components=1 -xvf phoenix-*-jvm.tar

# Indicate that the container listens on port 9740
EXPOSE 9740

# Expose default data directory as VOLUME
VOLUME [ "/phoenix" ]

# Run the daemon
ENTRYPOINT ["/phoenix/bin/phoenixd", "--agree-to-terms-of-service", "--http-bind-ip", "0.0.0.0"]
