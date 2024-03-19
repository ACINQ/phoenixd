import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests

buildscript {
    dependencies {
        classpath("app.cash.sqldelight:gradle-plugin:2.0.1")
    }
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("app.cash.sqldelight") version "2.0.1"
}

allprojects {
    group = "fr.acinq.lightning"
    version = "0.1-SNAPSHOT"

    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        mavenCentral()
        google()
    }
}

kotlin {
    jvm()

    fun KotlinNativeTargetWithHostTests.phoenixBinaries() {
        binaries {
            executable("phoenixd") {
                entryPoint = "fr.acinq.lightning.bin.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
            executable("phoenix-cli") {
                entryPoint = "fr.acinq.lightning.cli.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
        }
    }

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            phoenixBinaries()
        }
    }

    if (currentOs.isMacOsX) {
        macosX64 {
            phoenixBinaries()
        }
    }

    val ktorVersion = "2.3.8"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    sourceSets {
        commonMain {
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp:1.6.2-FEECREDIT-1")
                // ktor serialization
                implementation(ktor("serialization-kotlinx-json"))
                // ktor server
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation(ktor("server-auth"))
                implementation(ktor("server-status-pages")) // exception handling
                // ktor client (needed for webhook)
                implementation(ktor("client-core"))
                implementation(ktor("client-content-negotiation"))
                implementation(ktor("client-cio"))
                implementation(ktor("client-auth"))
                implementation(ktor("client-json"))

                implementation("com.squareup.okio:okio:3.8.0")
                implementation("com.github.ajalt.clikt:clikt:4.2.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")
            }
        }
        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
            }
        }
        nativeMain {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.0.1")
            }
        }
        macosMain {
            dependencies {
                implementation(ktor("client-darwin"))
            }
        }
    }
}

// forward std input when app is run via gradle (otherwise keyboard input will return EOF)
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

sqldelight {
    databases {
        create("PhoenixDatabase") {
            packageName.set("fr.acinq.phoenix.db")
            srcDirs.from("src/commonMain/sqldelight/phoenixdb")
        }
    }
}
