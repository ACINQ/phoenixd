import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
    application
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
            |package fr.acinq.phoenixd
            |
            |object BuildVersions {
            |    const val phoenixdCommit = "${gitCommitHash()}"
            |    const val phoenixdVersion = "${project.version}-${gitCommitHash().take(7)}"
            |    const val lightningKmpVersion = "${libs.versions.lightningkmp.get()}"
            |}
            |
            """.trimMargin()
        )
    ) {
        rename { "BuildVersions.kt" }
        into("fr/acinq/phoenixd")
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
                entryPoint = "fr.acinq.phoenixd.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
            executable("phoenix-cli") {
                entryPoint = "fr.acinq.phoenixd.cli.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
        }
    }

    if (currentOs.isLinux && arch != "aarch64") {
        // there is no kotlin native toolchain for linux arm64 yet, but we can still build for the JVM
        // see https://youtrack.jetbrains.com/issue/KT-51794/Cant-run-JVM-targets-on-ARM-Linux-when-using-Kotlin-Multiplatform-plugin
        linuxX64 {
            compilations["main"].cinterops.create("sqlite") {
                // use sqlite3 amalgamation on linux tests to prevent linking issues on new linux distros with dependency libraries which are to recent (for example glibc)
                // see: https://github.com/touchlab/SQLiter/pull/38#issuecomment-867171789
                definitionFile.set(File("$rootDir/src/nativeInterop/cinterop/sqlite3.def"))
            }
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
                implementation("fr.acinq.lightning:lightning-kmp-core:${libs.versions.lightningkmp.get()}")
                // ktor serialization
                implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
                // ktor server
                implementation("io.ktor:ktor-server-core:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-server-cio:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-server-websockets:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-server-auth:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-server-status-pages:${libs.versions.ktor.get()}") // exception handling
                // ktor client (needed for webhook)
                implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-client-auth:${libs.versions.ktor.get()}")
                implementation("io.ktor:ktor-client-json:${libs.versions.ktor.get()}")

                implementation("org.jetbrains.kotlinx:kotlinx-io-core:${libs.versions.kotlinx.io.get()}")
                implementation("com.github.ajalt.clikt:clikt:${libs.versions.clikt.get()}")
                implementation("co.touchlab:kermit-io:${libs.versions.kermit.io.get()}")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}")
                implementation("io.ktor:ktor-client-okhttp:${libs.versions.ktor.get()}")
                implementation("ch.qos.logback:logback-classic:${libs.versions.test.logback.get()}")
            }
        }
        nativeMain {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:${libs.versions.sqldelight.get()}")
            }
        }
        if (currentOs.isLinux) {
            linuxMain {
                dependencies {
                    implementation("io.ktor:ktor-client-curl:${libs.versions.ktor.get()}")
                }
            }
        }
        if (currentOs.isMacOsX) {
            macosMain {
                dependencies {
                    implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
                }
            }
        }
    }
}

application {
    mainClass = "fr.acinq.phoenixd.PhoenixdKt"
}

val cliScripts by tasks.register("cliScripts", CreateStartScripts::class) {
    mainClass.set("fr.acinq.phoenixd.cli.PhoenixCliKt")
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

// print errors to console in native tests
tasks.withType<KotlinNativeTest> {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
        showStackTraces = true
    }
}

sqldelight {
    // On Linux we build libsqlite locally using cinterops
    linkSqlite = !currentOs.isLinux
    databases {
        create("PhoenixDatabase") {
            packageName.set("fr.acinq.phoenixd.db.sqldelight")
            srcDirs.from("src/commonMain/sqldelight/phoenixdb")
        }
    }
}

