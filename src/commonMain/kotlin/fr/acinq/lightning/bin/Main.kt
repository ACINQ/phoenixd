package fr.acinq.lightning.bin

import app.cash.sqldelight.EnumColumnAdapter
import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.clikt.sources.MapValueSource
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.BuildVersions
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.bin.conf.EnvVars.PHOENIX_SEED
import fr.acinq.lightning.bin.conf.LSP
import fr.acinq.lightning.bin.conf.PhoenixSeed
import fr.acinq.lightning.bin.conf.getOrGenerateSeed
import fr.acinq.lightning.bin.conf.readConfFile
import fr.acinq.lightning.bin.db.SqliteChannelsDb
import fr.acinq.lightning.bin.db.SqlitePaymentsDb
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.bin.db.payments.LightningOutgoingQueries
import fr.acinq.lightning.bin.json.ApiType
import fr.acinq.lightning.bin.logs.FileLogWriter
import fr.acinq.lightning.bin.logs.TimestampFormatter
import fr.acinq.lightning.bin.logs.stringTimestamp
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceClient
import fr.acinq.lightning.blockchain.mempool.MempoolSpaceWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.ChannelsDb
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.PaymentsDb
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenix.db.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.FileSystem
import okio.buffer
import okio.use
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>) = Phoenixd()
    .versionOption(BuildVersions.phoenixdVersion, names = setOf("--version", "-v"))
    .main(args)

class Phoenixd : CliktCommand() {
    private val confFile = datadir / "phoenix.conf"
    private val seed by option("--seed", help = "Manually provide a 12-words seed", hidden = true, envvar = PHOENIX_SEED)
        .convert { PhoenixSeed(MnemonicCode.toSeed(it, "").toByteVector(), isNew = false) }
        .defaultLazy {
            val value = getOrGenerateSeed(datadir)
            if (value.isNew) {
                terminal.print(yellow("Generating new seed..."))
                runBlocking { delay(500.milliseconds) }
                terminal.println(white("done"))
            }
            value
        }
    private val agreeToTermsOfService by option("--agree-to-terms-of-service", hidden = true, help = "Agree to terms of service").flag()
    private val chain by option("--chain", help = "Bitcoin chain to use").choice(
        "mainnet" to Chain.Mainnet, "testnet" to Chain.Testnet
    ).default(Chain.Mainnet, defaultForHelp = "mainnet")
    private val mempoolSpaceUrl by option("--mempool-space-url", help = "Custom mempool.space instance")
        .convert { Url(it) }
        .defaultLazy {
            when (chain) {
                Chain.Mainnet -> MempoolSpaceClient.OfficialMempoolMainnet
                Chain.Testnet -> MempoolSpaceClient.OfficialMempoolTestnet
                else -> error("unsupported chain")
            }
        }
    private val mempoolPollingInterval by option("--mempool-space-polling-interval-minutes", help = "Polling interval for mempool.space API", hidden = true)
        .int().convert { it.minutes }
        .default(10.minutes)
    private val httpBindIp by option("--http-bind-ip", help = "Bind ip for the http api").default("127.0.0.1")
    private val httpBindPort by option("--http-bind-port", help = "Bind port for the http api").int().default(9740)
    private val httpPassword by option("--http-password", help = "Password for the http api").defaultLazy {
        // the additionalValues map already contains values in phoenix.conf, so if we are here then there are no existing password
        terminal.print(yellow("Generating default api password..."))
        val value = randomBytes32().toHex()
        FileSystem.SYSTEM.appendingSink(confFile, mustExist = false).buffer().use { it.writeUtf8("\nhttp-password=$value\n") }
        terminal.println(white("done"))
        value
    }
    private val webHookUrl by option("--webhook", help = "Webhook http endpoint for push notifications (alternative to websocket)")
        .convert { Url(it) }

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
        ).default(100_000.sat, "100k")
        private val maxRelativeFeePct by option("--max-relative-fee-percent", help = "Max relative fee for on-chain operations in percent.", hidden = true)
            .int()
            .restrictTo(1..50)
            .default(30)
        val maxRelativeFeeBasisPoints get() = maxRelativeFeePct * 100
    }

    private val liquidityOptions by LiquidityOptions()

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
        FileSystem.SYSTEM.createDirectories(datadir)
        context {
            valueSource = MapValueSource(readConfFile(confFile))
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

    @OptIn(DelicateCoroutinesApi::class)
    override fun run() {
        if (seed.isNew && !agreeToTermsOfService) {
            runBlocking {
                terminal.println(green("Backup"))
                terminal.println("This software is self-custodial, you have full control and responsibility over your funds.")
                terminal.println("Your 12-words seed is located in ${FileSystem.SYSTEM.canonicalize(datadir)}, ${bold(red("make sure to do a backup or you risk losing your funds"))}.")
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
                terminal.println("Press any key to continue...")
                terminal.readLineOrNull(true)
                terminal.println()
            }
        }
        consoleLog(cyan("datadir: ${FileSystem.SYSTEM.canonicalize(datadir)}"))
        consoleLog(cyan("chain: $chain"))
        consoleLog(cyan("autoLiquidity: ${liquidityOptions.autoLiquidity}"))

        val scope = GlobalScope
        val loggerFactory = LoggerFactory(
            StaticConfig(minSeverity = Severity.Info, logWriterList = buildList {
                // always log to file
                add(FileLogWriter(datadir / "phoenix.log", scope))
                // only log to console if verbose mode is enabled
                if (verbosity == Verbosity.Verbose) add(CommonWriter(TimestampFormatter))
            })
        )
        val lsp = LSP.from(chain)
        val liquidityPolicy = LiquidityPolicy.Auto(
            maxMiningFee = liquidityOptions.maxMiningFee,
            maxRelativeFeeBasisPoints = liquidityOptions.maxRelativeFeeBasisPoints,
            skipMiningFeeCheck = false,
            maxAllowedCredit = liquidityOptions.maxFeeCredit
        )
        val keyManager = LocalKeyManager(seed.seed, chain, lsp.swapInXpub)
        val nodeParams = NodeParams(chain, loggerFactory, keyManager)
            .copy(
                zeroConfPeers = setOf(lsp.walletParams.trampolineNode.id),
                liquidityPolicy = MutableStateFlow(liquidityPolicy),
            )
        consoleLog(cyan("nodeid: ${nodeParams.nodeId}"))

        val driver = createAppDbDriver(datadir, chain, nodeParams.nodeId)
        val database = PhoenixDatabase(
            driver = driver,
            lightning_outgoing_payment_partsAdapter = Lightning_outgoing_payment_parts.Adapter(
                part_routeAdapter = LightningOutgoingQueries.hopDescAdapter,
                part_status_typeAdapter = EnumColumnAdapter()
            ),
            lightning_outgoing_paymentsAdapter = Lightning_outgoing_payments.Adapter(
                status_typeAdapter = EnumColumnAdapter(),
                details_typeAdapter = EnumColumnAdapter()
            ),
            incoming_paymentsAdapter = Incoming_payments.Adapter(
                origin_typeAdapter = EnumColumnAdapter(),
                received_with_typeAdapter = EnumColumnAdapter()
            ),
            channel_close_outgoing_paymentsAdapter = Channel_close_outgoing_payments.Adapter(
                closing_info_typeAdapter = EnumColumnAdapter()
            ),
            inbound_liquidity_outgoing_paymentsAdapter = Inbound_liquidity_outgoing_payments.Adapter(
                lease_typeAdapter = EnumColumnAdapter()
            )
        )
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
                                it is PaymentEvents.PaymentReceived && it.amount > 0.msat -> {
                                    val metadata = paymentsDb.metadataQueries.get(WalletPaymentId.IncomingPaymentId(it.paymentHash))
                                    emit(ApiType.PaymentReceived(it, metadata))
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
                    .filterIsInstance<PaymentEvents.PaymentReceived>()
                    .filter { it.amount > 0.msat }
                    .collect {
                        consoleLog("received lightning payment: ${it.amount.truncateToSatoshi()} (${it.receivedWith.joinToString { part -> part::class.simpleName.toString().lowercase() }})")
                    }
            }
            launch {
                nodeParams.nodeEvents
                    .filterIsInstance<LiquidityEvents.Decision.Rejected>()
                    .collect {
                        when (val reason = it.reason) {
                            is LiquidityEvents.Decision.Rejected.Reason.OverMaxCredit -> {
                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): over max fee credit (max=${reason.maxAllowedCredit})"))
                            }
                            is LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverMaxMiningFee -> {
                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): over max mining fee (max=${reason.maxMiningFee})"))
                            }
                            is LiquidityEvents.Decision.Rejected.Reason.TooExpensive.OverRelativeFee -> {
                                consoleLog(yellow("lightning payment rejected (amount=${it.amount.truncateToSatoshi()}): fee=${it.fee.truncateToSatoshi()} more than ${reason.maxRelativeFeeBasisPoints.toDouble() / 100}% of amount"))
                            }
                            LiquidityEvents.Decision.Rejected.Reason.ChannelInitializing -> {
                                consoleLog(yellow("channels are initializing"))
                            }
                            LiquidityEvents.Decision.Rejected.Reason.PolicySetToDisabled -> {
                                consoleLog(yellow("automated liquidity is disabled"))
                            }
                        }
                    }
            }
            launch {
                nodeParams.feeCredit
                    .drop(1) // we drop the initial value which is 0 sat
                    .collect { feeCredit -> consoleLog("fee credit: $feeCredit") }
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
            peer.registerFcmToken("super-${randomBytes32().toHex()}")
            peer.setAutoLiquidityParams(liquidityOptions.autoLiquidity)
        }

        val server = embeddedServer(CIO, port = httpBindPort, host = httpBindIp,
            configure = {
                reuseAddress = true
            },
            module = {
                Api(nodeParams, peer, eventsFlow, httpPassword, webHookUrl).run { module() }
            }
        )
        val serverJob = scope.launch {
            try {
                server.start(wait = true)
            } catch (t: Throwable) {
                if (t.cause?.message?.contains("Address already in use") == true) {
                    consoleLog(t.cause?.message.toString(), err = true)
                } else throw t
            }
        }

        server.environment.monitor.subscribe(ServerReady) {
            consoleLog("listening on http://$httpBindIp:$httpBindPort")
        }
        server.environment.monitor.subscribe(ApplicationStopPreparing) {
            consoleLog(brightYellow("shutting down..."))
            listeners.cancel()
            peerConnectionLoop.cancel()
            peer.disconnect()
            server.stop()
            exitProcess(0)
        }
        server.environment.monitor.subscribe(ApplicationStopped) { consoleLog(brightYellow("http server stopped")) }

        runBlocking { serverJob.join() }
    }

}