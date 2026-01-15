package fr.acinq.phoenixd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.LocalKeyManager.Companion.nodeKeyBasePath
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenixd.BuildVersions
import fr.acinq.phoenixd.conf.ListValueSource
import fr.acinq.phoenixd.conf.readConfFile
import fr.acinq.phoenixd.datadir
import fr.acinq.phoenixd.payments.Parser
import fr.acinq.phoenixd.payments.lnurl.helpers.LnurlParser
import fr.acinq.phoenixd.payments.lnurl.models.Lnurl
import fr.acinq.phoenixd.payments.lnurl.models.LnurlAuth
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.println
import kotlin.use

fun main(args: Array<String>) =
    PhoenixCli()
        .versionOption(BuildVersions.phoenixdVersion, names = setOf("--version", "-v"))
        .subcommands(
            RecoverSeedLastWords(),
            RecoverSeedOneWrongWord()
        )
        .main(args)

class PhoenixCli : CliktCommand() {
    override fun run() {
    }
}

class RecoverSeedLastWords : CliktCommand(name = "recoverseedlastwords", help = "Recover last two words of a seed", printHelpOnEmptyArgs = true) {
    private val chain by option("--chain", help = "bitcoin chain to use")
        .choice(
            "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
        ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val nodeId by option("--node-id", "-n", help = "expected node id")
        .convert { PublicKey.fromHex(it) }.required()
    private val words by option("--words", "-w", help = "first 10 seed words, comma-separated")
        .split(",").required()
        .validate {
            require(it.size == 10) { "--words must contain exactly 10 words" }
            it.forEach { word -> require(MnemonicCode.englishWordlist.contains(word)) { "'$word' is not a valid word" } }
        }
    private val parallelism by option("--parallelism", "-p", help = "number of threads")
        .int().default(8)

    @OptIn(ExperimentalAtomicApi::class)
    override fun run() {

        val threadPool = Executors.newFixedThreadPool(parallelism)
        val wordQueue = ConcurrentLinkedQueue(MnemonicCode.englishWordlist)
        val stopFlag = AtomicBoolean(false)

        repeat(parallelism) { workerId ->
            threadPool.submit {
                while (!stopFlag.load()) {
                    val word11 = wordQueue.poll() ?: break // null means queue is empty
                    println("Processing word11: $word11")
                    MnemonicCode.englishWordlist.forEach { word12 ->
                        val mnemonics = words + word11 + word12
                        val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
                        val master = DeterministicWallet.generate(seed)
                        val nodeKey = master.derivePrivateKey(nodeKeyBasePath(chain))
                        if (nodeKey.publicKey == nodeId) {
                            println("Found!")
                            println("word11=$word11")
                            println("word12=$word12")
                            stopFlag.store(true)
                        }
                    }
                }
            }
        }

        threadPool.shutdown()

    }
}

class RecoverSeedOneWrongWord : CliktCommand(name = "recoverseedonewrongword", help = "Recover one wrong word of a seed", printHelpOnEmptyArgs = true) {
    private val chain by option("--chain", help = "bitcoin chain to use")
        .choice(
            "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
        ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val nodeId by option("--node-id", "-n", help = "expected node id")
        .convert { PublicKey.fromHex(it) }.required()
    private val words by option("--words", "-w", help = "12 seed words, comma-separated (containing one expected wrong word)")
        .split(",").required()
        .validate {
            require(it.size == 12) { "--words must contain exactly 12 words" }
            it.forEach { word -> require(MnemonicCode.englishWordlist.contains(word)) { "'$word' is not a valid word" } }
        }

    @OptIn(ExperimentalAtomicApi::class)
    override fun run() {

        val threadPool = Executors.newFixedThreadPool(12)
        val stopFlag = AtomicBoolean(false)

        for (i in 0 until 12) {
            threadPool.submit {
                val mnemonics = words.toMutableList()
                for (word in MnemonicCode.englishWordlist) {
                    //println("Processing word $word for pos $i")
                    mnemonics[i] = word
                    val mnemonics = words.toMutableList().apply { this[i] = word }
                    val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
                    val master = DeterministicWallet.generate(seed)
                    val nodeKey = master.derivePrivateKey(nodeKeyBasePath(chain))
                    if (nodeKey.publicKey == nodeId) {
                        println("Found!")
                        println("seed=${mnemonics.joinToString()}")
                        stopFlag.store(true)
                    }
                    if (stopFlag.load()) {
                        break
                    }
                }
            }
        }

        threadPool.shutdown()

    }
}

operator fun Url.div(path: String) = Url(URLBuilder(this).appendPathSegments(path))

fun String.toByteVector32(): ByteVector32 = kotlin.runCatching { ByteVector32.fromValidHex(this) }.recover { error("'$this' is not a valid 32-bytes hex string") }.getOrThrow()