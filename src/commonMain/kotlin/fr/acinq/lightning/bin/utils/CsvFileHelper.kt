package fr.acinq.lightning.bin.utils

import fr.acinq.lightning.bin.datadir
import fr.acinq.lightning.utils.currentTimestampSeconds
import okio.FileSystem

object CsvFileHelper {

    fun writeCsvFile(rows: List<String>) {
        val csvDir = datadir / "export"
        if (!FileSystem.SYSTEM.exists(csvDir)) {
            FileSystem.SYSTEM.createDirectories(csvDir)
        }
        val csvFile = csvDir / "export-${currentTimestampSeconds()}.csv"
        if (!FileSystem.SYSTEM.exists(csvFile)) {
            FileSystem.SYSTEM.write(csvFile) {
                rows.forEach { writeUtf8("\n$it") }
            }
        }
    }
}