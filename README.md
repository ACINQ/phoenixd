[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg?style=flat&logo=kotlin)](http://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![](https://img.shields.io/badge/www-Homepage-green.svg)](https://phoenix.acinq.co/server)
[![](https://img.shields.io/badge/www-API_doc-red.svg)](https://phoenix.acinq.co/server/api)

# phoenixd

**phoenixd** is the server equivalent of the popular [phoenix wallet](https://github.com/ACINQ/phoenix) for mobile.
It is written in [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and runs natively on Linux, MacOS (x86 and ARM), and Windows (WSL).

## Build

### Native Linux/WSL x64

Requires `libsqlite-dev` and `libcurl4-gnutls-dev`, both compiled against `glibc 2.19`.

```shell
./gradlew linuxX64DistZip
```

If you are on a system with a different glibc, try to use nix and build phoenixd inside the shell that you can create with the command `nix-shell .nix/shell.nix`.

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
./gradlew distZip
```
