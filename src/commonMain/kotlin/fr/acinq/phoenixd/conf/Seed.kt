package fr.acinq.phoenixd.conf

import com.github.ajalt.clikt.core.UsageError
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenixd.Phoenixd
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

data class PhoenixSeed(val seed: ByteVector, val isNew: Boolean, val path: Path?)

/**
 * @return a pair with the seed and a boolean indicating whether the seed was newly generated
 */
fun Phoenixd.SeedSpec.SeedPath.getOrGenerateSeed(): PhoenixSeed {
    try {
        val (mnemonics, isNew) = if (SystemFileSystem.exists(path)) {
            val contents = SystemFileSystem.source(path).buffered().use { it.readString() }
            val mnemonics = Regex("[a-z]+").findAll(contents).map { it.value }.toList()
            mnemonics to false
        } else {
            val entropy = randomBytes(16)
            val mnemonics = MnemonicCode.toMnemonics(entropy)
            SystemFileSystem.sink(path).buffered().use { it.writeString(mnemonics.joinToString(" ")) }
            mnemonics to true
        }
        MnemonicCode.validate(mnemonics)
        return PhoenixSeed(seed = MnemonicCode.toSeed(mnemonics, "").toByteVector(), isNew = isNew, path = path)
    } catch (t: Throwable) {
        throw UsageError(t.message, paramName = "seed")
    }
}