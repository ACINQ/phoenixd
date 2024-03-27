FROM eclipse-temurin:21-jdk-alpine AS BUILD

ARG PHOENIXD_BRANCH=v0.1.3
ARG PHOENIXD_COMMIT_HASH=d805f81c2bfb8a09a726bb36278216e607100a16

# Upgrade all packages and install dependencies
RUN apk update \
    && apk upgrade --no-interactive
RUN apk add --no-cache bash git

# Set necessary args and environment variables for building phoenixd
ARG PHOENIXD_BRANCH
ARG PHOENIXD_COMMIT_HASH

# Git pull phoenixd source at specified tag/branch and compile phoenixd binary
WORKDIR /phoenixd
RUN git clone --recursive --single-branch --branch ${PHOENIXD_BRANCH} -c advice.detachedHead=false \
    https://github.com/ACINQ/phoenixd . \
    && test `git rev-parse HEAD` = ${PHOENIXD_COMMIT_HASH} || exit 1 \
    && ./gradlew distTar

FROM eclipse-temurin:21-jre-alpine

# Upgrade all packages and install dependencies
RUN apk update \
    && apk upgrade --no-interactive
RUN apk add --no-cache bash

# Create a phoenix group and user
RUN addgroup -S phoenix -g 1000 \
    && adduser -S phoenix -G phoenix -u 1000 -h /phoenix
USER phoenix

WORKDIR /phoenix
COPY --chown=phoenix --from=BUILD /phoenixd/build/distributions/phoenix-*-jvm.tar .
RUN tar --strip-components=1 -xvf phoenix-*-jvm.tar
RUN mkdir .phoenix

# Indicate that the container listens on port 9740
EXPOSE 9740

# Run the daemon
ENTRYPOINT ["/phoenix/bin/phoenixd", "--agree-to-terms-of-service", "--http-bind-ip", "0.0.0.0"]
