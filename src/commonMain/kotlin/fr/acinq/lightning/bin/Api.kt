package fr.acinq.lightning.bin

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.toEither
import fr.acinq.lightning.BuildVersions
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.bin.db.SqlitePaymentsDb
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.bin.json.ApiType.*
import fr.acinq.lightning.bin.payments.AddressResolver
import fr.acinq.lightning.bin.payments.Parser
import fr.acinq.lightning.bin.payments.PayDnsAddress
import fr.acinq.lightning.bin.payments.lnurl.LnurlHandler
import fr.acinq.lightning.bin.payments.lnurl.helpers.LnurlParser
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlAuth
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlPay
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlWithdraw
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Closed
import fr.acinq.lightning.channel.states.Closing
import fr.acinq.lightning.channel.states.ClosingFeerates
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.OfferTypes
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.seconds

class Api(
    private val nodeParams: NodeParams,
    private val peer: Peer,
    private val eventsFlow: SharedFlow<ApiEvent>,
    private val password: String,
    private val webhookUrl: Url?,
    private val webhookSecret: String,
    private val loggerFactory: LoggerFactory,
) {

    @OptIn(ExperimentalSerializationApi::class)
    fun Application.module() {

        val payDnsAddress = PayDnsAddress()
        val lnurlHandler = LnurlHandler(loggerFactory, nodeParams.keyManager as LocalKeyManager)
        val addressResolver = AddressResolver(payDnsAddress, lnurlHandler)

        val json = Json {
            prettyPrint = true
            isLenient = true
            explicitNulls = false
            serializersModule = fr.acinq.lightning.json.JsonSerializers.json.serializersModule
        }

        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            timeoutMillis = 10_000
            pingPeriodMillis = 10_000
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = cause.message ?: "", status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError)
            }
            status(HttpStatusCode.Unauthorized) { call, status ->
                call.respondText(text = "Invalid authentication (use basic auth with the http password set in phoenix.conf)", status = status)
            }
            status(HttpStatusCode.MethodNotAllowed) { call, status ->
                call.respondText(text = "Invalid http method (use the correct GET/POST)", status = status)
            }
            status(HttpStatusCode.NotFound) { call, status ->
                call.respondText(text = "Unknown endpoint (check api doc)", status = status)
            }
        }
        install(Authentication) {
            basic {
                validate { credentials ->
                    if (credentials.password == password) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }
        }

        routing {
            authenticate {
                get("getinfo") {
                    val info = NodeInfo(
                        nodeId = nodeParams.nodeId,
                        channels = peer.channels.values.map { Channel.from(it) },
                        chain = nodeParams.chain.name.lowercase(),
                        blockHeight = peer.currentTipFlow.value,
                        version = BuildVersions.phoenixdVersion
                    )
                    call.respond(info)
                }
                get("getbalance") {
                    val balance = peer.channels.values
                        .filterIsInstance<ChannelStateWithCommitments>()
                        .filterNot { it is Closing || it is Closed }
                        .map { it.commitments.active.first().availableBalanceForSend(it.commitments.params, it.commitments.changes) }
                        .sum().truncateToSatoshi()
                    call.respond(Balance(balance, nodeParams.feeCredit.value))
                }
                get("listchannels") {
                    call.respond(peer.channels.values.toList())
                }
                post("createinvoice") {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters.getOptionalLong("amountSat")?.sat
                    val maxDescriptionSize = 128
                    val description = formParameters["description"]
                        ?.also { if (it.length > maxDescriptionSize) badRequest("Request parameter description is too long (max $maxDescriptionSize characters)") }
                    val descriptionHash = formParameters.getOptionalByteVector32("descriptionHash")
                    val eitherDesc = when {
                        description != null && descriptionHash == null -> Either.Left(description)
                        description == null && descriptionHash != null -> Either.Right(descriptionHash)
                        else -> badRequest("Must provide either a description or descriptionHash")
                    }
                    val invoice = peer.createInvoice(randomBytes32(), amount?.toMilliSatoshi(), eitherDesc)
                    formParameters["externalId"]?.takeUnless { it.isBlank() }?.let { externalId ->
                        paymentDb.metadataQueries.insertExternalId(WalletPaymentId.IncomingPaymentId(invoice.paymentHash), externalId)
                    }
                    call.respond(GeneratedInvoice(invoice.amount?.truncateToSatoshi(), invoice.paymentHash, serialized = invoice.write()))
                }
                get("getoffer") {
                    call.respond(nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).first.encode())
                }
                get("getlnaddress") {
                    if (peer.channels.isEmpty()) {
                        call.respond("must have one channel")
                    } else {
                        val address = peer.requestAddress("en")
                        call.respond("₿$address")
                    }
                }
                get("payments/incoming") {
                    val listAll = call.parameters["all"]?.toBoolean() ?: false // by default, only list incoming payments that have been received
                    val externalId = call.parameters["externalId"] // may filter incoming payments by an external id
                    val from = call.parameters.getOptionalLong("from") ?: 0L
                    val to = call.parameters.getOptionalLong("to") ?: currentTimestampMillis()
                    val limit = call.parameters.getOptionalLong("limit") ?: 20
                    val offset = call.parameters.getOptionalLong("offset") ?: 0

                    val payments = if (externalId.isNullOrBlank()) {
                        paymentDb.listIncomingPayments(from, to, limit, offset, listAll)
                    } else {
                        paymentDb.listIncomingPaymentsForExternalId(externalId, from, to, limit, offset, listAll)
                    }.map { (payment, externalId) ->
                        IncomingPayment(payment, externalId)
                    }
                    call.respond(payments)
                }
                get("payments/incoming/{paymentHash}") {
                    val paymentHash = call.parameters.getByteVector32("paymentHash")
                    paymentDb.getIncomingPayment(paymentHash)?.let {
                        val metadata = paymentDb.metadataQueries.get(WalletPaymentId.IncomingPaymentId(paymentHash))
                        call.respond(IncomingPayment(it, metadata?.externalId))
                    } ?: call.respond(HttpStatusCode.NotFound)
                }
                get("payments/outgoing") {
                    val listAll = call.parameters["all"]?.toBoolean() ?: false // by default, only list outgoing payments that have been successfully sent, or are pending
                    val from = call.parameters.getOptionalLong("from") ?: 0L
                    val to = call.parameters.getOptionalLong("to") ?: currentTimestampMillis()
                    val limit = call.parameters.getOptionalLong("limit") ?: 20
                    val offset = call.parameters.getOptionalLong("offset") ?: 0
                    val payments = paymentDb.listLightningOutgoingPayments(from, to, limit, offset, listAll).map {
                        OutgoingPayment(it)
                    }
                    call.respond(payments)
                }
                get("payments/outgoing/{uuid}") {
                    val uuid = call.parameters.getUUID("uuid")
                    paymentDb.getLightningOutgoingPayment(uuid)?.let {
                        call.respond(OutgoingPayment(it))
                    } ?: call.respond(HttpStatusCode.NotFound)
                }
                post("payinvoice") {
                    val formParameters = call.receiveParameters()
                    val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                    val invoice = formParameters.getInvoice("invoice")
                    val amount = (overrideAmount ?: invoice.amount) ?: missing("amountSat")
                    when (val event = peer.payInvoice(amount, invoice)) {
                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                        is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                    }
                }
                post("payoffer") {
                    val formParameters = call.receiveParameters()
                    val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                    val offer = formParameters.getOffer("offer")
                    val amount = (overrideAmount ?: offer.amount) ?: missing("amountSat")
                    val note = formParameters["message"]
                    when (val event = peer.payOffer(amount, offer, payerKey = nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).second, payerNote = note, fetchInvoiceTimeout = 30.seconds)) {
                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                        is fr.acinq.lightning.io.OfferNotPaid -> call.respond(PaymentFailed(event))
                    }
                }
                post("paylnaddress") {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters.getLong("amountSat").sat.toMilliSatoshi()
                    val (username, domain) = formParameters.getEmailLikeAddress("address")
                    val note = formParameters["message"]
                    when (val res = addressResolver.resolveAddress(username, domain, amount, note)) {
                        is Try.Success -> when (val either = res.result) {
                            is Either.Left -> {
                                // LNURL
                                val lnurlInvoice = either.value
                                when (val event = peer.payInvoice(amount, lnurlInvoice.invoice)) {
                                    is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                    is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                    is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                                }
                            }
                            is Either.Right -> {
                                // OFFER
                                val offer = either.value
                                when (val event = peer.payOffer(amount, offer, payerKey = nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).second, payerNote = note, fetchInvoiceTimeout = 30.seconds)) {
                                    is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                    is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                    is fr.acinq.lightning.io.OfferNotPaid -> call.respond(PaymentFailed(event))
                                }
                            }
                        }
                        is Try.Failure -> error("cannot resolve address: ${res.error.message}")
                    }
                }
                post("decodeinvoice") {
                    val formParameters = call.receiveParameters()
                    val invoice = formParameters.getInvoice("invoice")
                    call.respond(invoice)
                }
                post("decodeoffer") {
                    val formParameters = call.receiveParameters()
                    val offer = formParameters.getOffer("offer")
                    call.respond(offer)
                }
                post("lnurlpay") {
                    val formParameters = call.receiveParameters()
                    val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                    val comment = formParameters["message"]
                    val request = formParameters.getLnurl("lnurl")
                    // early abort to avoid executing an invalid url
                    when (request) {
                        is LnurlAuth -> badRequest("this is an authentication lnurl")
                        is Lnurl.Request -> if (request.tag == Lnurl.Tag.Withdraw) badRequest("this is a withdraw lnurl")
                        else -> Unit
                    }
                    try {
                        val lnurl = lnurlHandler.executeLnurl(request.initialUrl)
                        when (lnurl) {
                            is LnurlWithdraw -> badRequest("this is a withdraw lnurl")
                            is LnurlPay.PaymentParameters -> {
                                val amount = (overrideAmount ?: lnurl.minSendable)
                                val invoice = lnurlHandler.getLnurlPayInvoice(lnurl, amount, comment)
                                when (val event = peer.payInvoice(amount, invoice.invoice)) {
                                    is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                    is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                    is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                                }
                            }
                            else -> badRequest("invalid [${lnurl::class}] lnurl=${lnurl.initialUrl}")
                        }
                    } catch (e: Exception) {
                        badRequest(e.message ?: e::class.toString())
                    }
                }
                post("lnurlwithdraw") {
                    val formParameters = call.receiveParameters()
                    val request = formParameters.getLnurl("lnurl")
                    // early abort to avoid executing an invalid url
                    when (request) {
                        is LnurlAuth -> badRequest("this is an authentication lnurl")
                        is Lnurl.Request -> if (request.tag == Lnurl.Tag.Pay) badRequest("this is a payment lnurl")
                        else -> Unit
                    }
                    try {
                        val lnurl = lnurlHandler.executeLnurl(request.initialUrl)
                        when (lnurl) {
                            is LnurlPay -> badRequest("this is a payment lnurl")
                            is LnurlWithdraw -> {
                                val invoice = peer.createInvoice(randomBytes32(), lnurl.maxWithdrawable, Either.Left(lnurl.defaultDescription))
                                lnurlHandler.sendWithdrawInvoice(lnurl, invoice)
                                call.respond(LnurlWithdrawResponse(lnurl, invoice))
                            }
                            else -> badRequest("invalid [${lnurl::class}] lnurl=${lnurl.initialUrl}")
                        }
                    } catch (e: Exception) {
                        badRequest(e.message ?: e::class.toString())
                    }
                }
                post("lnurlauth") {
                    val formParameters = call.receiveParameters()
                    val request = formParameters.getLnurl("lnurl")
                    if (request !is LnurlAuth) badRequest("this is a payment or withdraw lnurl")
                    try {
                        lnurlHandler.signAndSendAuthRequest(request)
                        call.respond("authentication success")
                    } catch (e: Exception) {
                        badRequest("could not authenticate: ${e.message ?: e::class.toString()}")
                    }
                }
                post("sendtoaddress") {
                    val res = kotlin.runCatching {
                        val formParameters = call.receiveParameters()
                        val amount = formParameters.getLong("amountSat").sat
                        val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                        val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                        peer.spliceOut(amount, scriptPubKey, feerate)
                    }.toEither()
                    when (res) {
                        is Either.Right -> when (val r = res.value) {
                            is ChannelCommand.Commitment.Splice.Response.Created -> call.respondText(r.fundingTxId.toString())
                            is ChannelCommand.Commitment.Splice.Response.Failure -> call.respondText(r.toString())
                            else -> call.respondText("no channel available")
                        }
                        is Either.Left -> call.respondText(res.value.message.toString())
                    }
                }
                post("closechannel") {
                    val formParameters = call.receiveParameters()
                    val channelId = formParameters.getByteVector32("channelId")
                    val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                    val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                    peer.send(WrappedChannelCommand(channelId, ChannelCommand.Close.MutualClose(scriptPubKey, ClosingFeerates(feerate))))
                    call.respondText("ok")
                }
                webSocket("/websocket") {
                    try {
                        eventsFlow.collect { sendSerialized(it) }
                    } catch (e: Throwable) {
                        println("onError ${closeReason.await()?.message}")
                    }
                }
            }
        }

        webhookUrl?.let { url ->
            val client = HttpClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(json = Json {
                        prettyPrint = true
                        isLenient = true
                    })
                }
            }
            client.sendPipeline.intercept(HttpSendPipeline.State) {
                when (val body = context.body) {
                    is TextContent -> {
                        val bodyBytes = body.text.encodeUtf8()
                        val secretBytes = webhookSecret.encodeUtf8()
                        val sig = bodyBytes.hmacSha256(secretBytes)
                        context.headers.append("X-Phoenix-Signature", sig.hex())
                    }
                }
            }
            launch {
                eventsFlow.collect { event ->
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(event)
                    }
                }
            }
        }
    }

    private val paymentDb: SqlitePaymentsDb by lazy { peer.db.payments as SqlitePaymentsDb }

    private fun missing(argName: String): Nothing = throw MissingRequestParameterException(argName)

    private fun invalidType(argName: String, typeName: String): Nothing = throw ParameterConversionException(argName, typeName)

    private fun badRequest(message: String): Nothing = throw BadRequestException(message)

    private fun Parameters.getString(argName: String): String = (this[argName] ?: missing(argName))

    private fun Parameters.getByteVector32(argName: String): ByteVector32 = getString(argName).let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull() ?: invalidType(argName, "hex32") }

    private fun Parameters.getOptionalByteVector32(argName: String): ByteVector32? = this[argName]?.let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull() ?: invalidType(argName, "hex32") }

    private fun Parameters.getUUID(argName: String): UUID = getString(argName).let { uuid -> kotlin.runCatching { UUID.fromString(uuid) }.getOrNull() ?: invalidType(argName, "uuid") }

    private fun Parameters.getAddressAndConvertToScript(argName: String): ByteVector = Script.write(Bitcoin.addressToPublicKeyScript(nodeParams.chainHash, getString(argName)).right ?: badRequest("Invalid address")).toByteVector()

    private fun Parameters.getInvoice(argName: String): Bolt11Invoice = getString(argName).let { invoice -> Bolt11Invoice.read(invoice).getOrElse { invalidType(argName, "bolt11invoice") } }

    private fun Parameters.getOffer(argName: String): OfferTypes.Offer = getString(argName).let { invoice -> OfferTypes.Offer.decode(invoice).getOrElse { invalidType(argName, "offer") } }

    private fun Parameters.getLong(argName: String): Long = ((this[argName] ?: missing(argName)).toLongOrNull()) ?: invalidType(argName, "integer")

    private fun Parameters.getOptionalLong(argName: String): Long? = this[argName]?.let { it.toLongOrNull() ?: invalidType(argName, "integer") }

    private fun Parameters.getEmailLikeAddress(argName: String): Pair<String, String> = this[argName]?.let { Parser.parseEmailLikeAddress(it) } ?: invalidType(argName, "username@domain")

    private fun Parameters.getLnurl(argName: String): Lnurl = this[argName]?.let { LnurlParser.extractLnurl(it) } ?: missing(argName)

    private fun Parameters.getLnurlAuth(argName: String): LnurlAuth = this[argName]?.let { LnurlParser.extractLnurl(it) as LnurlAuth } ?: missing(argName)
}
