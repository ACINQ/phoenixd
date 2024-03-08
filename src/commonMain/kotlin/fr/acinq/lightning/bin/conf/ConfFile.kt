package fr.acinq.lightning.bin.conf

import okio.FileSystem
import okio.Path

fun readConfFile(confFile: Path): Map<String, String> = try {
    buildMap {
        if (FileSystem.SYSTEM.exists(confFile)) {
            FileSystem.SYSTEM.read(confFile) {
                while (true) {
                    val line = readUtf8Line() ?: break
                    line.split("=").run { put(first(), last()) }
                }
            }
        }
    }
} catch (t: Throwable) {
    emptyMap()
}