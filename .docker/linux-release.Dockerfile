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
RUN ./gradlew packageLinuxX64

# Copy the build to host machine
FROM ubuntu:18.04 as final
COPY --from=BUILD /phoenixd/build/distributions/* .

