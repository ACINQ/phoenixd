package fr.acinq.phoenixd.conf

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readLine

fun readConfFile(confFile: Path): List<Pair<String, String>> =
    buildList {
        if (SystemFileSystem.exists(confFile)) {
            SystemFileSystem.source(confFile).buffered().use {
                while (true) {
                    val line = it.readLine() ?: break
                    line.split("=").run { add(first() to last()) }
                }
            }
        }
    }