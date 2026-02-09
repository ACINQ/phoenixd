package fr.acinq.phoenixd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import fr.acinq.bitcoin.*
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.crypto.LocalKeyManager.Companion.nodeKeyBasePath
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenixd.conf.LSP
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.system.exitProcess

class RecoveryTools : CliktCommand() {
    override fun run() {
    }
}

//class RecoverSeedLastWords : CliktCommand(name = "recoverseedlastwords", help = "Recover last two words of a seed", printHelpOnEmptyArgs = true) {
//    private val chain by option("--chain", help = "bitcoin chain to use")
//        .choice(
//            "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
//        ).default(Chain.Mainnet, defaultForHelp = "mainnet")
//    private val nodeId by option("--node-id", "-n", help = "expected node id")
//        .convert { PublicKey.fromHex(it) }.required()
//    private val words by option("--words", "-w", help = "first 10 seed words, comma-separated")
//        .split(",").required()
//        .validate {
//            require(it.size == 10) { "--words must contain exactly 10 words" }
//            it.forEach { word -> require(MnemonicCode.englishWordlist.contains(word)) { "'$word' is not a valid word" } }
//        }
//    private val parallelism by option("--parallelism", "-p", help = "number of threads")
//        .int().default(8)
//
//    @OptIn(ExperimentalAtomicApi::class)
//    override fun run() {
//
//        val threadPool = Executors.newFixedThreadPool(parallelism)
//        val wordQueue = ConcurrentLinkedQueue(MnemonicCode.englishWordlist)
//        val stopFlag = AtomicBoolean(false)
//
//        repeat(parallelism) { workerId ->
//            threadPool.submit {
//                while (!stopFlag.load()) {
//                    val word11 = wordQueue.poll() ?: break // null means queue is empty
//                    println("Processing word11: $word11")
//                    MnemonicCode.englishWordlist.forEach { word12 ->
//                        val mnemonics = words + word11 + word12
//                        val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
//                        val master = DeterministicWallet.generate(seed)
//                        val nodeKey = master.derivePrivateKey(nodeKeyBasePath(chain))
//                        if (nodeKey.publicKey == nodeId) {
//                            println("Found!")
//                            println("word11=$word11")
//                            println("word12=$word12")
//                            stopFlag.store(true)
//                        }
//                    }
//                }
//            }
//        }
//
//        threadPool.shutdown()
//
//    }
//}

class RecoverSeedOneWrongWord : CliktCommand(
    name = "recoverseedonewrongword",
    help = "Recover one wrong word of a seed",
    printHelpOnEmptyArgs = true
) {
    private val chain by option("--chain", help = "bitcoin chain to use")
        .choice(
            "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
        ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val nodeId by option("--node-id", "-n", help = "expected node id")
        .convert { PublicKey.fromHex(it) }.required()
    private val words by option(
        "--words",
        "-w",
        help = "12 seed words, comma-separated (containing one expected wrong word)"
    )
        .split(",").required()
        .validate {
            require(it.size == 12) { "--words must contain exactly 12 words" }
            it.forEach { word -> require(MnemonicCode.englishWordlist.contains(word)) { "'$word' is not a valid word" } }
        }

    @OptIn(ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
    override fun run() {

        val dispatcher = newFixedThreadPoolContext(12, "fixed-pool")

        runBlocking {
            coroutineScope {
                for (i in 0 until 12) {
                    launch(dispatcher) {
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
                                exitProcess(0)
                            }
                            ensureActive()
                        }
                    }
                }
            }
        }

        dispatcher.close()

    }
}

class ClaimMainLocalCommit : CliktCommand(
    name = "claimmainlocalcommit",
    help = "Manually build a claim-main output tx in the local commit case.",
    printHelpOnEmptyArgs = true
) {
    private val chain by option("--chain", help = "Bitcoin chain to use")
        .choice(
            "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
        ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val words by option("--mnemonics", help = "12 seed words, comma-separated")
        .split(",").required()
        .validate {
            require(it.size == 12) { "--mnemonics must contain exactly 12 words" }
            it.forEach { word -> require(MnemonicCode.englishWordlist.contains(word)) { "'$word' is not a valid word" } }
        }
    private val fundingKeyPath by option("--funding-key-path", help = "Funding key path, e.g. m/xxx/yyy/zzz")
        .convert { KeyPath(it) }
        .required()
    private val remoteRevocationBasePoint by option("--revocation-basepoint", help = "Remote revocation basepoint")
        .convert { PublicKey.fromHex(it) }
        .required()
    private val commitTx by option("--commit-tx", help = "Local commit tx in hex")
        .convert { Transaction.read(it) }
        .required()
    private val commitmentFormat by option("--commitment-format", help = "Commitment format").choice(
        "anchor-outputs" to Transactions.CommitmentFormat.AnchorOutputs,
        "simple-taproot" to Transactions.CommitmentFormat.SimpleTaprootChannels
    ).required()
    private val address by option("--address", help = "Destination bitcoin address (where the funds will be sent to).")
        .required()
        .check { runCatching { Base58Check.decode(it) }.isSuccess || runCatching { Bech32.decodeWitnessAddress(it) }.isSuccess }
    private val feerate by option("--feerate-sat-byte", help = "Feerate for the claim transaction").int()
        .convert { FeeratePerKw(FeeratePerByte(it.sat)) }
        .required()

    override fun run() {
        val lsp = LSP.from(chain)
        val keyManager = LocalKeyManager(
            MnemonicCode.toSeed(words, "").byteVector(),
            chain,
            lsp.swapInXpub
        )
        val localChannelParams = LocalChannelParams(
            nodeId = randomKey().publicKey(),
            fundingKeyPath = fundingKeyPath,
            isChannelOpener = true,
            paysCommitTxFees = false,
            defaultFinalScriptPubKey = ByteVector.empty,
            features = Features.empty
        )
        val remoteChannelParams = RemoteChannelParams(
            nodeId = randomKey().publicKey(),
            revocationBasepoint = remoteRevocationBasePoint,
            paymentBasepoint = randomKey().publicKey(),
            delayedPaymentBasepoint = randomKey().publicKey(),
            htlcBasepoint = randomKey().publicKey(),
            features = Features.empty
        )
        val channelParams = ChannelParams(
            channelId = randomBytes32(),
            channelConfig = ChannelConfig.standard,
            channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features),
            localParams = localChannelParams,
            remoteParams = remoteChannelParams,
            channelFlags = ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = false),
        )
        val localDustLimit = Satoshi(546)
        val toLocalDelay = CltvExpiryDelta(720)
        val finalScript = Script.write(Bitcoin.addressToPublicKeyScript(chain.chainHash, address).right!!).toByteVector()

        val claimMainTx = (0L..1000).firstNotNullOfOrNull { commitIndex ->
            val localKeys = keyManager.channelKeys(fundingKeyPath).localCommitmentKeys(channelParams, commitIndex)
            Transactions.ClaimLocalDelayedOutputTx.createUnsignedTx(
                commitKeys = localKeys,
                commitTx = commitTx,
                localDustLimit = localDustLimit,
                toLocalDelay = toLocalDelay,
                localFinalScriptPubKey = finalScript,
                feerate = feerate,
                commitmentFormat = commitmentFormat
            )
                .map { it.sign().tx }
                .right
        }
        when(claimMainTx) {
            null -> {
                println("Unable to generate a valid claim tx.")
            }
            else -> {
                claimMainTx.correctlySpends(listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                println("Success!")
                println(claimMainTx)
            }

        }

    }
}
