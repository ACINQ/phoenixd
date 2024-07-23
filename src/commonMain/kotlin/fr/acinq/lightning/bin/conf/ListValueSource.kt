package fr.acinq.lightning.bin.conf

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.sources.ValueSource
import okio.Path

/**
 * Very similar to [com.github.ajalt.clikt.sources.MapValueSource], but backed by a [List]
 * in order to allow multiple invocations (e.g. ```--arg foo --arg bar```).
 */
class ListValueSource(
    private val values: List<Pair<String, String>>,
    private val getKey: (Context, Option) -> String = ValueSource.getKey(joinSubcommands = "."),
) : ValueSource {
    override fun getValues(context: Context, option: Option): List<ValueSource.Invocation> {
        return values
            .filter { it.first == (option.valueSourceKey ?: getKey(context, option)) }
            .map { ValueSource.Invocation.value(it.second) }
    }

    companion object {
        fun fromFile(confFile: Path): ListValueSource = ListValueSource(readConfFile(confFile))
    }
}