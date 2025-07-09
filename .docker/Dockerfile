# Use Ubuntu image for building for compatibility with macOS arm64 builds
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build

# Set necessary args and environment variables for building phoenixd
ARG TARGETPLATFORM
ARG PHOENIXD_BRANCH=v0.6.1
ARG PHOENIXD_COMMIT_HASH=b8545c123d0a07cc71794aca4b16e914ca0cc2b4

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y --no-install-recommends bash git \
    && apt clean

# Git pull phoenixd source at specified tag/branch and compile phoenixd
WORKDIR /phoenixd
RUN git clone --recursive --single-branch --branch ${PHOENIXD_BRANCH} -c advice.detachedHead=false \
    https://github.com/ACINQ/phoenixd . \
    && test `git rev-parse HEAD` = ${PHOENIXD_COMMIT_HASH} || exit 1

RUN case "${TARGETPLATFORM}" in \
        "linux/amd64") ./gradlew linkPhoenixdReleaseExecutableLinuxX64 linkPhoenix-cliReleaseExecutableLinuxX64 ;; \
        "linux/arm64") ./gradlew linkPhoenixdReleaseExecutableLinuxArm64 linkPhoenix-cliReleaseExecutableLinuxArm64 ;; \
        *) echo "Unsupported TARGETPLATFORM: ${TARGETPLATFORM}" && exit 1 ;; \
    esac

# Slim image to minimize final image size (Alpine is smaller but not glibc-based)
FROM debian:bookworm-slim AS final

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends bash ca-certificates \
    && apt clean

# Create a phoenix group and user
RUN addgroup --system phoenix --gid 1000 \
    && adduser --system phoenix --ingroup phoenix --uid 1000 --home /phoenix
USER phoenix

# Unpack the release
WORKDIR /phoenix
COPY --chown=phoenix:phoenix --from=build /phoenixd/build/bin/*/phoenixdReleaseExecutable/phoenixd.kexe phoenixd
COPY --chown=phoenix:phoenix --from=build /phoenixd/build/bin/*/phoenix-cliReleaseExecutable/phoenix-cli.kexe phoenix-cli

# Indicate that the container listens on port 9740
EXPOSE 9740

# Create the data directory so permissions are preserved when mounted as a volume (otherwise would be mounted as root)
RUN mkdir -p /phoenix/.phoenix
# Expose default data directory as VOLUME
VOLUME [ "/phoenix/.phoenix" ]

# Run the daemon
ENTRYPOINT ["/phoenix/phoenixd", "--agree-to-terms-of-service", "--http-bind-ip", "0.0.0.0"]
