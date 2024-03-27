FROM eclipse-temurin:21-jdk-jammy AS BUILD

# Set necessary args and environment variables for building phoenixd
ARG PHOENIXD_BRANCH=v0.1.3
ARG PHOENIXD_COMMIT_HASH=d805f81c2bfb8a09a726bb36278216e607100a16

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

FROM eclipse-temurin:21-jre-jammy

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y --no-install-recommends bash \
    && apt clean

# Set phoenix user and group with static IDs
ARG GROUP_ID=1000
ARG USER_ID=1000
RUN groupadd -g ${GROUP_ID} phoenix \
    && useradd -u ${USER_ID} -g phoenix -d /phoenix phoenix \
    && mkdir /phoenix \
    && chown -R phoenix:phoenix /phoenix
USER phoenix:phoenix

# Unpack the release
WORKDIR /phoenix
COPY --chown=phoenix --from=BUILD /phoenixd/build/distributions/phoenix-*-jvm.tar .
RUN tar --strip-components=1 -xvf phoenix-*-jvm.tar

# Indicate that the container listens on port 9740
EXPOSE 9740

# Run the daemon
ENTRYPOINT ["/phoenix/bin/phoenixd", "--agree-to-terms-of-service", "--http-bind-ip", "0.0.0.0"]
