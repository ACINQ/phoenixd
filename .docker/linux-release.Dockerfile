FROM ubuntu:18.04 as build

ARG CURL_VERSION=7.88.1

# Upgrade all packages and install dependencies
RUN apt-get update \
    && apt-get upgrade -y
RUN apt-get install -y --no-install-recommends \
        ca-certificates \
        openjdk-17-jdk \
        openssh-client \
        libgnutls28-dev \
        libsqlite3-dev  \
        build-essential \
        git \
        wget \
    && apt clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

WORKDIR /curl
RUN wget https://curl.se/download/curl-${CURL_VERSION}.tar.bz2 \
    && tar -xjvf curl-${CURL_VERSION}.tar.bz2 \
    && cd curl-${CURL_VERSION} \
    && ./configure --with-gnutls=/lib/x86_64-linux-gnu/ \
    && make \
    && make install \
    && ldconfig

# Copy phoenixd source
WORKDIR /phoenixd
COPY . .

# Build
RUN \
    --mount=type=cache,target=/phoenixd/build \
    --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/phoenixd/.gradle \
    --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    ./gradlew --build-cache packageLinuxX64 && \
    mkdir /phoenixd/build-distributions && \
    cp -R /phoenixd/build/distributions/* /phoenixd/build-distributions/ && \
    ./gradlew --stop

# Copy the build to host machine
FROM ubuntu:18.04 as final
COPY --from=BUILD /phoenixd/build-distributions/* .

