import Versions.ktor
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import java.io.ByteArrayOutputStream

buildscript {
    dependencies {
        classpath("app.cash.sqldelight:gradle-plugin:${Versions.sqlDelight}")
    }
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version Versions.kotlin
    kotlin("plugin.serialization") version Versions.kotlin
    id("app.cash.sqldelight") version Versions.sqlDelight
    application
}

allprojects {
    group = "fr.acinq.lightning"
    version = "0.4.3-SNAPSHOT"

    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        mavenCentral()
        google()
    }
}

/** Get the current git commit hash. */
fun gitCommitHash(): String {
    val stream = ByteArrayOutputStream()
    project.exec {
        commandLine = "git rev-parse --verify --long HEAD".split(" ")
        standardOutput = stream
    }
    return String(stream.toByteArray()).split("\n").first()
}

/**
 * Generates a `BuildVersions` file in build/generated-src containing the current git commit and the lightning-kmp version.
 * See https://stackoverflow.com/a/74771876 for details.
 */
val buildVersionsTask by tasks.registering(Sync::class) {
    group = "build"
    from(
        resources.text.fromString(
            """
            |package fr.acinq.lightning
            |
            |object BuildVersions {
            |    const val phoenixdCommit = "${gitCommitHash()}"
            |    const val phoenixdVersion = "${project.version}-${gitCommitHash().take(7)}"
            |    const val lightningKmpVersion = "${Versions.lightningKmp}"
            |}
            |
            """.trimMargin()
        )
    ) {
        rename { "BuildVersions.kt" }
        into("fr/acinq/lightning")
    }
    into(layout.buildDirectory.dir("generated/kotlin/"))
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()
val arch = System.getProperty("os.arch")

kotlin {
    jvm {
        withJava()
    }

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

    if (currentOs.isLinux && arch != "aarch64") {
        // there is no kotlin native toolchain for linux arm64 yet, but we can still build for the JVM
        // see https://youtrack.jetbrains.com/issue/KT-51794/Cant-run-JVM-targets-on-ARM-Linux-when-using-Kotlin-Multiplatform-plugin
        linuxX64 {
            phoenixBinaries()
        }
    }

    if (currentOs.isMacOsX) {
        macosX64 {
            phoenixBinaries()
        }
        macosArm64 {
            phoenixBinaries()
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(buildVersionsTask.map { it.destinationDir })
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp-core:${Versions.lightningKmp}")
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
                implementation(ktor("client-auth"))
                implementation(ktor("client-json"))

                implementation("com.squareup.okio:okio:${Versions.okio}")
                implementation("com.github.ajalt.clikt:clikt:${Versions.clikt}")
                implementation("app.cash.sqldelight:coroutines-extensions:${Versions.sqlDelight}")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:${Versions.sqlDelight}")
                implementation(ktor("client-okhttp"))
                implementation("ch.qos.logback:logback-classic:1.2.3")
            }
        }
        nativeMain {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:${Versions.sqlDelight}")
            }
        }
        if (currentOs.isLinux) {
            linuxMain {
                dependencies {
                    implementation(ktor("client-curl"))
                }
            }
        }
        if (currentOs.isMacOsX) {
            macosMain {
                dependencies {
                    implementation(ktor("client-darwin"))
                }
            }
        }
    }
}

application {
    mainClass = "fr.acinq.lightning.bin.MainKt"
}

val cliScripts by tasks.register("cliScripts", CreateStartScripts::class) {
    mainClass.set("fr.acinq.lightning.cli.PhoenixCliKt")
    outputDir = tasks.startScripts.get().outputDir
    classpath = tasks.startScripts.get().classpath
    applicationName = "phoenix-cli"
}

tasks.startScripts {
    dependsOn(cliScripts)
}

distributions {
    main {
        distributionBaseName = "phoenix"
        distributionClassifier = "jvm"
    }
    fun Distribution.configureNativeDistribution(buildTask: String, dir: String, classifier: String) {
        distributionBaseName = "phoenix"
        distributionClassifier = classifier
        contents {
            from(tasks[buildTask])
            from("$projectDir/build/bin/$dir/phoenixdReleaseExecutable") {
                include("*.kexe")
                rename("phoenixd.kexe", "phoenixd")
            }
            from("$projectDir/build/bin/$dir/phoenix-cliReleaseExecutable") {
                include("*.kexe")
                rename("phoenix-cli.kexe", "phoenix-cli")
            }
        }
    }
    if (currentOs.isLinux && arch != "aarch64") {
        create("linuxX64") {
            configureNativeDistribution("linuxX64Binaries", "linuxX64", "linux-x64")
        }
    }
    if (currentOs.isMacOsX) {
        create("macosX64") {
            configureNativeDistribution("macosX64Binaries", "macosX64", "macos-x64")
        }
        create("macosArm64") {
            configureNativeDistribution("macosArm64Binaries", "macosArm64", "macos-arm64")
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
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:${Versions.sqlDelight}")
            packageName.set("fr.acinq.phoenix.db")
            srcDirs.from("src/commonMain/sqldelight/phoenixdb")
        }
    }
}

