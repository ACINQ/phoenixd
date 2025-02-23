package fr.acinq.phoenixd.logs

import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import kotlinx.datetime.*
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char

object TimestampFormatter : MessageStringFormatter {
    override fun formatMessage(severity: Severity?, tag: Tag?, message: Message): String {
        val sb = StringBuilder()
        sb.append(stringTimestamp())
        sb.append(" ")
        sb.append(super.formatMessage(severity, tag, message))
        return sb.toString()
    }
}

private val dateFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
}

fun stringTimestamp() = dateFormat.format(Clock.System.now().toLocalDateTime(TimeZone.UTC))