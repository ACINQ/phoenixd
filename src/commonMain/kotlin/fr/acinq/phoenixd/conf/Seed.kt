package fr.acinq.phoenixd.conf

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.utils.toByteVector
import okio.FileSystem
import okio.Path

data class PhoenixSeed(val seed: ByteVector, val isNew: Boolean)

/**
 * @return a pair with the seed and a boolean indicating whether the seed was newly generated
 */
fun getOrGenerateSeed(dir: Path): PhoenixSeed {
    val file = dir / "seed.dat"
    val (mnemonics, isNew) = if (FileSystem.SYSTEM.exists(file)) {
        val contents = FileSystem.SYSTEM.read(file) { readUtf8() }
        val mnemonics = Regex("[a-z]+").findAll(contents).map { it.value }.toList()
        mnemonics to false
    } else {
        val entropy = randomBytes(16)
        val mnemonics = MnemonicCode.toMnemonics(entropy)
        FileSystem.SYSTEM.write(file) { writeUtf8(mnemonics.joinToString(" ")) }
        mnemonics to true
    }
    MnemonicCode.validate(mnemonics)
    return PhoenixSeed(seed = MnemonicCode.toSeed(mnemonics, "").toByteVector(), isNew = isNew)
}