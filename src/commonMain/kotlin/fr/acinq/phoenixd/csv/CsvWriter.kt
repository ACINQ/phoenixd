package fr.acinq.phoenixd.csv

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * A generic class for writing CSV files.
 */
open class CsvWriter(path: Path) {

    private val sink: Sink

    init {
        path.parent?.let { dir -> SystemFileSystem.createDirectories(dir) }
        sink = SystemFileSystem.sink(path).buffered()
    }

    fun addRow(vararg fields: String) {
        val cleanFields = fields.map { processField(it) }
        sink.writeString(cleanFields.joinToString(separator = ",", postfix = "\n"))
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

