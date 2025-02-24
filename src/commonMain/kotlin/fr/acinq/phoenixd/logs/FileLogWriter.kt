package fr.acinq.phoenixd.logs

import co.touchlab.kermit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

class FileLogWriter(private val logFile: Path, scope: CoroutineScope, private val messageStringFormatter: MessageStringFormatter = TimestampFormatter) : LogWriter() {
    private val mailbox: Channel<String> = Channel(Channel.BUFFERED)

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        mailbox.trySend(messageStringFormatter.formatMessage(severity, Tag(tag), Message(message)))
        throwable?.run { mailbox.trySend(stackTraceToString()) }
    }

    init {
        scope.launch {
            val sink = SystemFileSystem.sink(logFile, append = true).buffered()
            mailbox.consumeAsFlow().collect { logLine ->
                val sb = StringBuilder()
                sb.append(logLine)
                sb.appendLine()
                sink.writeString(sb.toString())
                sink.flush()
            }
        }
    }
}