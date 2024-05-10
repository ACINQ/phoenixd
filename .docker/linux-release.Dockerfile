# Use debian
FROM debian:bullseye-slim AS BUILD

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y bash git libsqlite3-dev libcurl4-gnutls-dev openjdk-17-jdk \
    && apt clean

# Git pull phoenixd source at specified tag/branch and compile phoenixd
WORKDIR /phoenixd

# Copy the repository to the container
COPY . .

# Compile the package
RUN ./gradlew packageLinuxX64

# Alpine image to minimize final image size
FROM eclipse-temurin:21-jre-alpine as FINAL

COPY --from=BUILD /phoenixd/build/distributions/phoenix-*-jvm.tar .
