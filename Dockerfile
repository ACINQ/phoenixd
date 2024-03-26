FROM eclipse-temurin:17-jdk-alpine AS BUILD

ARG PHOENIXD_BRANCH=v0.1.2
ARG PHOENIXD_COMMIT_HASH=414aa56bfa15c0b215ed89d0b96fd6c43cd8c1e3
ARG LIGHTNING_KMP_BRANCH=v1.6.2-FEECREDIT-4
ARG LIGHTNING_KMP_COMMIT_HASH=eba5a5bf7d7d77bd59cb8e38ecd20ec72d288672

# Upgrade all packages and install dependencies
RUN apk update \
    && apk upgrade --no-interactive
RUN apk add --no-cache bash git

# Set necessary args and environment variables for building phoenixd
ARG PHOENIXD_BRANCH
ARG PHOENIXD_COMMIT_HASH
ARG LIGHTNING_KMP_BRANCH
ARG LIGHTNING_KMP_COMMIT_HASH

# Build dependencies
WORKDIR /lightning-kmp
RUN git clone --recursive --single-branch --branch ${LIGHTNING_KMP_BRANCH} -c advice.detachedHead=false \
    https://github.com/ACINQ/lightning-kmp . \
    && test `git rev-parse HEAD` = ${LIGHTNING_KMP_COMMIT_HASH} || exit 1 \
    && ./gradlew publishToMavenLocal -x dokkaHtml

# Git pull phoenixd source at specified tag/branch and compile phoenixd binary
WORKDIR /phoenixd
RUN git clone --recursive --single-branch --branch ${PHOENIXD_BRANCH} -c advice.detachedHead=false \
    https://github.com/ACINQ/phoenixd . \
    && test `git rev-parse HEAD` = ${PHOENIXD_COMMIT_HASH} || exit 1 \
    && ./gradlew distTar

FROM eclipse-temurin:17-jre-alpine

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
