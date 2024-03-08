package fr.acinq.lightning.bin.logs

import co.touchlab.kermit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.buffer

class FileLogWriter(private val logFile: Path, scope: CoroutineScope, private val messageStringFormatter: MessageStringFormatter = DefaultFormatter) : LogWriter() {
    private val mailbox: Channel<String> = Channel(Channel.BUFFERED)

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        mailbox.trySend(messageStringFormatter.formatMessage(severity, Tag(tag), Message(message)))
        throwable?.run { mailbox.trySend(stackTraceToString()) }
    }

    init {
        scope.launch {
            val sink = FileSystem.SYSTEM.appendingSink(logFile).buffer()
            mailbox.consumeAsFlow().collect { logLine ->
                val sb = StringBuilder()
                sb.append(logLine)
                sb.appendLine()
                sink.writeUtf8(sb.toString())
                sink.flush()
            }
        }
    }
}