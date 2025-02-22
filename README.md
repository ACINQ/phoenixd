[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.10-blue.svg?style=flat&logo=kotlin)](http://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![](https://img.shields.io/badge/www-Homepage-green.svg)](https://phoenix.acinq.co/server)
[![](https://img.shields.io/badge/www-API_doc-red.svg)](https://phoenix.acinq.co/server/api)

# phoenixd

**phoenixd** is the server equivalent of the popular [phoenix wallet](https://github.com/ACINQ/phoenix) for mobile.
It is written in [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and runs natively on Linux, MacOS (x86 and ARM), and Windows (WSL).

## Build

### Requirements

- [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21)

### Native Linux/WSL x64

```shell
./gradlew linuxX64DistZip
```

### Native MacOS x64
```shell
./gradlew macosX64DistZip
```

### Native MacOS arm64
```shell
./gradlew macosArm64DistZip
```

### JVM
```shell
./gradlew jvmDistZip
```
