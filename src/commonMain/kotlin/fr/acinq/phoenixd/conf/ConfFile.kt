package fr.acinq.phoenixd.conf

import okio.FileSystem
import okio.Path

fun readConfFile(confFile: Path): List<Pair<String, String>> =
    buildList {
        if (FileSystem.SYSTEM.exists(confFile)) {
            FileSystem.SYSTEM.read(confFile) {
                while (true) {
                    val line = readUtf8Line() ?: break
                    line.split("=").run { add(first() to last()) }
                }
            }
        }
    }