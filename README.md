[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg?style=flat&logo=kotlin)](http://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![](https://img.shields.io/badge/www-Homepage-green.svg)](https://phoenix.acinq.co/server)
[![](https://img.shields.io/badge/www-API_doc-red.svg)](https://phoenix.acinq.co/server/api)

# phoenixd

**phoenixd** is the server equivalent of the popular [phoenix wallet](https://github.com/ACINQ/phoenix) for mobile.
It is written in [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and runs natively on Linux, MacOS (x86 and ARM), and Windows (WSL).

## Build

### Native Linux/WSL

Requires `libsqlite-dev` and `libcurl4-gnutls-dev`, both compiled against `glibc 2.19`.

```shell
./gradlew packageLinuxX64
```

### Native MacOS x64
```shell
./gradlew packageMacOSX64
```

### Native MacOS arm64
```shell
./gradlew packageMacOSArm64
```

### JVM
```shell
./gradlew distZip
```

## Build with Docker

### Native Linux/WSL

```shell
DOCKER_BUILDKIT=1 docker build --file .docker/linux-release.Dockerfile --output type=local,dest=./out .
```