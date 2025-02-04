package fr.acinq.phoenixd.csv

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * A generic class for writing CSV files.
 */
open class CsvWriter(path: Path) {

    private val sink: BufferedSink

    init {
        path.parent?.let { dir -> FileSystem.SYSTEM.createDirectories(dir) }
        sink = FileSystem.SYSTEM.sink(path, mustCreate = false).buffer()
    }

    fun addRow(vararg fields: String) {
        val cleanFields = fields.map { processField(it) }
        sink.writeUtf8(cleanFields.joinToString(separator = ",", postfix = "\n"))
    }

    fun addRow(fields: List<String>) {
        addRow(*fields.toTypedArray())
    }

    private fun processField(str: String): String {
        return str.findAnyOf(listOf(",", "\"", "\n"))?.let {
            // - field must be enclosed in double-quotes
            // - a double-quote appearing inside the field must be
            //   escaped by preceding it with another double quote
            "\"${str.replace("\"", "\"\"")}\""
        } ?: str
    }

    fun close() {
        sink.flush()
        sink.close()
    }
}

