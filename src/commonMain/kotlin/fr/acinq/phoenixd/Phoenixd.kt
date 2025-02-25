package fr.acinq.phoenixd

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.groups.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.*
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.*
import fr.acinq.phoenixd.conf.EnvVars.PHOENIX_SEED
import fr.acinq.phoenixd.conf.LSP
import fr.acinq.phoenixd.conf.ListValueSource
import fr.acinq.phoenixd.conf.PhoenixSeed
import fr.acinq.phoenixd.conf.getOrGenerateSeed
import fr.acinq.phoenixd.db.SqliteChannelsDb
import fr.acinq.phoenixd.db.SqlitePaymentsDb
import fr.acinq.phoenixd.db.createPhoenixDb
import fr.acinq.phoenixd.json.ApiType
import fr.acinq.phoenixd.logs.RollingFileLogWriter
import fr.acinq.phoenixd.logs.TimestampFormatter
import fr.acinq.phoenixd.logs.stringTimestamp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>) = Phoenixd()
    .versionOption(BuildVersions.phoenixdVersion, names = setOf("--version", "-v"))
    .main(args)

class Phoenixd : CliktCommand() {
    private val confFile = Path(datadir, "phoenix.conf")

    private val agreeToTermsOfService by option("--agree-to-terms-of-service", hidden = true, help = "Agree to terms of service").flag()
    private val chain by option("--chain", help = "Bitcoin chain to use").choice(
        "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet3
    ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val mempoolSpaceUrl by option("--mempool-space-url", help = "Custom mempool.space instance")
        .convert { Url(it) }
        .defaultLazy {
            when (chain) {
                Chain.Mainnet -> MempoolSpaceClient.OfficialMempoolMainnet
                Chain.Testnet3 -> MempoolSpaceClient.OfficialMempoolTestnet3
                else -> error("unsupported chain")
            }
        }
    private val mempoolPollingInterval by option("--mempool-space-polling-interval-minutes", help = "Polling interval for mempool.space API", hidden = true)
        .int().convert { it.minutes }
        .default(10.minutes)
    private val httpBindIp by option("--http-bind-ip", help = "Bind ip for the http api").default("127.0.0.1")
    private val httpBindPort by option("--http-bind-port", help = "Bind port for the http api").int().default(9740)
    private val httpPassword by option("--http-password", help = "Password for the http api (full access)")
        .defaultLazy {
            // if we are here then no value is defined in phoenix.conf
            terminal.print(yellow("Generating default api password..."))
            val value = randomBytes32().toHex()
            SystemFileSystem.sink(confFile, append = true).buffered().use { it.writeString("\nhttp-password=$value") }
            terminal.println(white("done"))
            value
        }
    private val httpPasswordLimitedAccess by option("--http-password-limited-access", help = "Password for the http api (limited access)")
        .defaultLazy {
            // if we are here then no value is defined in phoenix.conf
            terminal.print(yellow("Generating limited access api password..."))
            val value = randomBytes32().toHex()
            SystemFileSystem.sink(confFile, append = true).buffered().use { it.writeString("\nhttp-password-limited-access=$value") }
            terminal.println(white("done"))
            value
        }
    private val webHookUrls by option("--webhook", help = "Webhook http endpoint for push notifications (alternative to websocket)")
        .convert { Url(it) }
        .multiple()
    private val webHookSecret by option("--webhook-secret", help = "Secret used to authenticate webhook calls")
        .defaultLazy {
            // if we are here then no value is defined in phoenix.conf
            terminal.print(yellow("Generating webhook secret..."))
            val value = randomBytes32().toHex()
            SystemFileSystem.sink(confFile, append = true).buffered().use { it.writeString("\nwebhook-secret=$value") }
            terminal.println(white("done"))
            value
        }

    class LiquidityOptions : OptionGroup(name = "Liquidity Options") {
        val autoLiquidity by option("--auto-liquidity", help = "Amount automatically requested when inbound liquidity is needed").choice(
            "off" to 0.sat,
            "2m" to 2_000_000.sat,
            "5m" to 5_000_000.sat,
            "10m" to 10_000_000.sat,
        ).default(2_000_000.sat, "2m")
        val maxAbsoluteFee by option("--max-absolute-fee", hidden = true).deprecated("--max-absolute-fee is deprecated, use --max-mining-fee instead", error = true)
        val maxMiningFee by option("--max-mining-fee", help = "Max mining fee for on-chain operations, in satoshis")
            .int().convert { it.sat }
            .restrictTo(5_000.sat..200_000.sat)
            .defaultLazy("1% of auto-liquidity amount") {
                autoLiquidity * 1 / 100
            }
        val maxFeeCredit by option("--max-fee-credit", help = "Max fee credit, if reached payments will be rejected").choice(
            "off" to 0.sat,
            "50k" to 50_000.sat,
            "100k" to 100_000.sat,
        ).convert { it.toMilliSatoshi() }.default(100_000.sat.toMilliSatoshi(), "100k")
        private val maxRelativeFeePct by option("--max-relative-fee-percent", help = "Max relative fee for on-chain operations in percent.", hidden = true)
            .int()
            .restrictTo(1..50)
            .default(30)
        val maxRelativeFeeBasisPoints get() = maxRelativeFeePct * 100
    }

    private val liquidityOptions by LiquidityOptions()

    sealed class SeedSpec {
        data class Manual(val seed: ByteVector) : SeedSpec()
        data class SeedPath(val path: Path) : SeedSpec()
    }

    private val seedSpec by mutuallyExclusiveOptions(
        option("--seed", help = "Explicitly provide a 12-words seed", hidden = false, envvar = PHOENIX_SEED)
            .convert { SeedSpec.Manual(MnemonicCode.toSeed(it, "").toByteVector()) },
        option("--seed-path", help = "Override the path to the seed file", hidden = false)
            .convert { SeedSpec.SeedPath(Path(it)) }
    ).single().default(SeedSpec.SeedPath(Path(datadir, "seed.dat")))

    private val logRotateSize by option("--log-rotate-size", help = "Log rotate size in MB.")
        .long().convert { it * 1024 * 1024 }
        .default(10 * 1024 * 1024, "10")
    private val logRotateMaxFiles by option("--log-rotate-max-files", help = "Maximum number of log files kept.")
        .int()
        .default(5, "5")

    sealed class Verbosity {
        data object Default : Verbosity()
        data object Silent : Verbosity()
        data object Verbose : Verbosity()
    }

    private val verbosity by option(help = "Verbosity level").switch(
        "--silent" to Verbosity.Silent,
        "--verbose" to Verbosity.Verbose
    ).default(Verbosity.Default, defaultForHelp = "prints high-level info to the console")

    init {
        SystemFileSystem.createDirectories(datadir)
        context {
            valueSource = ListValueSource.fromFile(confFile)
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private fun consoleLog(msg: Any, err: Boolean = false) {
        if (verbosity == Verbosity.Default) { // in verbose mode we print logs instead
            terminal.print(gray(stringTimestamp()), stderr = err)
            terminal.print(" ")
            terminal.println(msg, stderr = err)
        }
    }

    override fun run() {
        val seed = when (val seedSpec = seedSpec) {
            is SeedSpec.Manual -> PhoenixSeed(seed = seedSpec.seed, isNew = false, path = null)
            is SeedSpec.SeedPath -> seedSpec.getOrGenerateSeed()
        }

        if (seed.isNew) {
            terminal.print(yellow("Generating new seed..."))
            runBlocking { delay(500.milliseconds) }
            terminal.println(white("done"))
        }

        if (seed.isNew && !agreeToTermsOfService) {
            runBlocking {
                terminal.println(green("Backup"))
                terminal.println("This software is self-custodial, you have full control and responsibility over your funds.")
                terminal.println("Your 12-words seed is located in ${seed.path}, ${bold(red("make sure to do a backup or you risk losing your funds"))}.")
                terminal.println("Do not share the same seed with other phoenix instances (mobile or server), it will cause issues and channel force closes.")
                terminal.println()
                terminal.prompt(
                    "Please confirm by typing",
                    choices = listOf("I understand"),
                    invalidChoiceMessage = "Please type those exact words:"
                )
                terminal.println()
                terminal.println(green("Continuous liquidity"))
                terminal.println(
                    """
                    Liquidity management is fully automated.
                    When receiving a Lightning payment that doesn't fit in your existing channel:
                    - If the payment amount is large enough to cover mining fees and service fees for automated liquidity, then your channel will be created or enlarged right away.
                    - If the payment is too small, then the full amount is added to your fee credit, and will be used later to pay for future fees. ${bold(red("The fee credit is non-refundable"))}.
                    The initial fee to create your channel can be quite significant. Please see this doc for help estimating what it will be in your case: ${bold("https://phoenix.acinq.co/server/auto-liquidity")}.
                    """.trimIndent()
                )
                terminal.println()
                terminal.prompt(
                    "Please confirm by typing",
                    choices = listOf("I understand"),
                    invalidChoiceMessage = "Please type those exact words:"
                )
                terminal.println()
                terminal.println("Phoenix server is about to start, use ${bold("phoenix-cli")} or the ${bold("http api")} to interact with the daemon. This message will not be displayed next time.")
                terminal.println(bold(yellow("Press any key to continue...")))
                terminal.readLineOrNull(true)
                terminal.println()
            }
        }
        consoleLog(cyan("datadir: $datadir"))
        consoleLog(cyan("chain: $chain"))
        consoleLog(cyan("autoLiquidity: ${liquidityOptions.autoLiquidity}"))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val loggerFactory = LoggerFactory(
            StaticConfig(minSeverity = Severity.Info, logWriterList = buildList {
                // always log to file
                add(RollingFileLogWriter(RollingFileLogWriterConfig(logFileName = "phoenix", logFilePath = Path(datadir.toString()), rollOnSize = logRotateSize, maxLogFiles = logRotateMaxFiles)))
                // only log to console if verbose mode is enabled
                if (verbosity == Verbosity.Verbose) add(CommonWriter(TimestampFormatter))
            })
        )
        val lsp = LSP.from(chain)
        val liquidityPolicy = LiquidityPolicy.Auto(
            inboundLiquidityTarget = liquidityOptions.autoLiquidity,
            maxAbsoluteFee = liquidityOptions.maxMiningFee,
            maxRelativeFeeBasisPoints = liquidityOptions.maxRelativeFeeBasisPoints,
            skipAbsoluteFeeCheck = false,
            considerOnlyMiningFeeForAbsoluteFeeCheck = true,
            maxAllowedFeeCredit = liquidityOptions.maxFeeCredit
        )
        val keyManager = LocalKeyManager(seed.seed, chain, lsp.swapInXpub)
        val nodeParams = NodeParams(chain, loggerFactory, keyManager)
            .copy(
                zeroConfPeers = setOf(lsp.walletParams.trampolineNode.id),
                liquidityPolicy = MutableStateFlow(liquidityPolicy),
            )
        consoleLog(cyan("nodeid: ${nodeParams.nodeId}"))
        consoleLog(cyan("offer: ${nodeParams.defaultOffer(lsp.walletParams.trampolineNode.id).first}"))

        val driver = createAppDbDriver(datadir, chain, nodeParams.nodeId)
        val database = createPhoenixDb(driver)
        val channelsDb = SqliteChannelsDb(driver, database)
        val paymentsDb = SqlitePaymentsDb(database)

        val mempoolSpace = MempoolSpaceClient(mempoolSpaceUrl, loggerFactory)
        val watcher = MempoolSpaceWatcher(mempoolSpace, scope, loggerFactory, pollingInterval = mempoolPollingInterval)
        val peer = Peer(
            nodeParams = nodeParams, walletParams = lsp.walletParams, client = mempoolSpace, watcher = watcher, db = object : Databases {
                override val channels: ChannelsDb get() = channelsDb
                override val payments: PaymentsDb get() = paymentsDb
            }, socketBuilder = TcpSocket.Builder(), scope
        )

        val eventsFlow: SharedFlow<ApiType.ApiEvent> = MutableSharedFlow<ApiType.ApiEvent>().run {
            scope.launch {
                launch {
                    nodeParams.nodeEvents
                        .collect {
                            when {
                                it is PaymentEvents.PaymentReceived && it.payment.amount > 0.msat -> {
                                    when (val payment = it.payment) {
                                        is LightningIncomingPayment -> {
                                            val metadata = paymentsDb.metadataQueries.get(payment.paymentHash)
                                            emit(ApiType.PaymentReceived(payment, metadata))
                                        }
                                        else -> {}
                                    }
                                }
                                else -> {}
                            }
                        }
                }
            }
            asSharedFlow()
        }

        val listeners = scope.launch {
            launch {
                // drop initial CLOSED event
                peer.connectionState.dropWhile { it is Connection.CLOSED }.collect {
                    when (it) {
                        Connection.ESTABLISHING -> consoleLog(yellow("connecting to lightning peer..."))
                        Connection.ESTABLISHED -> consoleLog(yellow("connected to lightning peer"))
                        is Connection.CLOSED -> consoleLog(yellow("disconnected from lightning peer"))
                    }
                }
            }
            launch {
                nodeParams.nodeEvents
                    .filterIsInstance<PaymentEvents>()
                    .collect {
                        when (it) {
                            is PaymentEvents.PaymentReceived -> when (val payment = it.payment) {
                                is LightningIncomingPayment -> {
                                    val fee = payment.parts.filterIsInstance<LightningIncomingPayment.Part.Htlc>().map { it.fundingFee?.amount ?: 0.msat }.sum().truncateToSatoshi()
                                    val type = payment.parts.joinToString { part -> part::class.simpleName.toString().lowercase() }
                                    consoleLog("received lightning payment: ${payment.amount.truncateToSatoshi()} ($type${if (fee > 0.sat) " fee=$fee" else ""})")
                                }
                                else -> {}
                            }
                            is PaymentEvents.PaymentSent ->
                                when (val payment = it.payment) {
                                    is AutomaticLiquidityPurchasePayment -> {
                                        val totalFee = payment.fees.truncateToSatoshi()
                                        val purchaseDetails = payment.liquidityPurchaseDetails
                                        val feePaidFromBalance = purchaseDetails.feePaidFromChannelBalance.total
                                        val feePaidFromFeeCredit = purchaseDetails.feeCreditUsed.truncateToSatoshi()
                                        val feeRemaining = totalFee - feePaidFromBalance - feePaidFromFeeCredit
                                        val purchaseType = purchaseDetails.purchase.paymentDetails.paymentType::class.simpleName.toString().lowercase()
                                        consoleLog("purchased inbound liquidity: ${purchaseDetails.purchase.amount} (totalFee=$totalFee feePaidFromBalance=$feePaidFromBalance feePaidFromFeeCredit=$feePaidFromFeeCredit feeRemaining=$feeRemaining purchaseType=$purchaseType)")
                                    }
                                    else -> {}
                                }
                        }
                    }
            }
            launch {
                nodeParams.nodeEvents
                    .filterIsInstance<LiquidityEvents.Rejected>()
                    .collect {
                        when (val reason = it.reason) {
                            // TODO: put this back after rework of LiquidityPolicy to handle fee credit
//                            is LiquidityEvents.Rejected.Reason.OverMaxCredit -> {
//                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): over max fee credit (max=${reason.maxAllowedCredit})"))
//                            }
                            is LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee ->
                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): over absolute fee (fee=${it.fee.truncateToSatoshi()} max=${reason.maxAbsoluteFee})"))
                            is LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee ->
                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): over relative fee (fee=${it.fee.truncateToSatoshi()} max=${reason.maxRelativeFeeBasisPoints.toDouble() / 100}%)"))
                            LiquidityEvents.Rejected.Reason.PolicySetToDisabled ->
                                consoleLog(yellow("automated liquidity is disabled"))
                            LiquidityEvents.Rejected.Reason.ChannelFundingInProgress ->
                                consoleLog(yellow("channel operation is in progress"))
                            is LiquidityEvents.Rejected.Reason.MissingOffChainAmountTooLow ->
                                consoleLog(yellow("missing offchain amount is too low (missingOffChainAmount=${reason.missingOffChainAmount} currentFeeCredit=${reason.currentFeeCredit}"))
                            LiquidityEvents.Rejected.Reason.NoMatchingFundingRate ->
                                consoleLog(yellow("no matching funding rates"))
                            is LiquidityEvents.Rejected.Reason.TooManyParts ->
                                consoleLog(yellow("too many payment parts"))
                        }
                    }
            }
            launch {
                peer.feeCreditFlow
                    .drop(1) // we drop the initial value which is 0 msat
                    .collect { feeCredit -> consoleLog("fee credit: ${feeCredit.truncateToSatoshi()}") }
            }
        }

        val peerConnectionLoop = scope.launch {
            while (true) {
                peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
                peer.connectionState.first { it is Connection.CLOSED }
                delay(3.seconds)
            }
        }

        runBlocking {
            peer.connectionState.first { it == Connection.ESTABLISHED }
        }

        val server = embeddedServer(
            CIO,
            environment = applicationEnvironment {

            },
            configure = {
                connector {
                    port = httpBindPort
                    host = httpBindIp
                }
                reuseAddress = true
            },
            module = {
                Api(nodeParams, peer, eventsFlow, httpPassword, httpPasswordLimitedAccess, webHookUrls, webHookSecret, loggerFactory).run { module() }
            }
        )

        fun stop() {
            scope.cancel()
            peer.disconnect()
            driver.close()
            exitProcess(0)
        }

        val serverJob = scope.launch {
            kotlin.runCatching {
                server.start(wait = true)
            }.onFailure {
                consoleLog(it.cause?.message.toString(), err = true)
                stop()
            }
        }

        server.monitor.subscribe(ServerReady) {
            consoleLog("listening on http://$httpBindIp:$httpBindPort")
        }
        server.monitor.subscribe(ApplicationStopPreparing) {
            consoleLog(brightYellow("shutting down..."))
            stop()
        }
        server.monitor.subscribe(ApplicationStopped) {
            consoleLog(brightYellow("http server stopped"))
        }

        runBlocking { serverJob.join() }
    }

}