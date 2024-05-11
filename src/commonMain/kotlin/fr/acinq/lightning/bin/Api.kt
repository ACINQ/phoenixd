package fr.acinq.lightning.bin

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.toEither
import fr.acinq.lightning.BuildVersions
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.bin.db.SqlitePaymentsDb
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.bin.json.ApiType.*
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Closed
import fr.acinq.lightning.channel.states.Closing
import fr.acinq.lightning.channel.states.ClosingFeerates
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.*
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
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

class Api(private val nodeParams: NodeParams, private val peer: Peer, private val eventsFlow: SharedFlow<ApiEvent>, private val password: String, private val webhookUrl: Url?, private val webhookSecret: String) {

    fun Application.module() {

        val json = Json {
            prettyPrint = true
            isLenient = true
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
                    var invoice : Bolt11Invoice

                    if ((formParameters["descriptionHash"]?.isNotBlank()) ?: false) {
                        val hash = formParameters.getByteVector32("descriptionHash")
                        invoice = peer.createInvoice(randomBytes32(), amount?.toMilliSatoshi(), Either.Right(hash))
                    } else {
                        val description = formParameters.getString("description")
                        invoice = peer.createInvoice(randomBytes32(), amount?.toMilliSatoshi(), Either.Left(description))
                    }

                    formParameters["externalId"]?.takeUnless { it.isBlank() }?.let { externalId ->
                        paymentDb.metadataQueries.insertExternalId(WalletPaymentId.IncomingPaymentId(invoice.paymentHash), externalId)
                    }
                    call.respond(GeneratedInvoice(invoice.amount?.truncateToSatoshi(), invoice.paymentHash, serialized = invoice.write()))
                }
                get("payments/incoming/{paymentHash}") {
                    val paymentHash = call.parameters.getByteVector32("paymentHash")
                    paymentDb.getIncomingPayment(paymentHash)?.let {
                        val metadata = paymentDb.metadataQueries.get(WalletPaymentId.IncomingPaymentId(paymentHash))
                        call.respond(IncomingPayment(it, metadata))
                    } ?: call.respond(HttpStatusCode.NotFound)
                }
                get("payments/incoming") {
                    val externalId = call.parameters.getString("externalId")
                    val metadataList = paymentDb.metadataQueries.getByExternalId(externalId)
                    metadataList.mapNotNull { (paymentId, metadata) ->
                        when (paymentId) {
                            is WalletPaymentId.IncomingPaymentId -> paymentDb.getIncomingPayment(paymentId.paymentHash)?.let {
                                IncomingPayment(it, metadata)
                            }
                            else -> null
                        }
                    }.let { payments ->
                        call.respond(payments)
                    }
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
                    when (val event = peer.sendLightning(amount, invoice)) {
                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
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

    private fun Parameters.getString(argName: String): String = (this[argName] ?: missing(argName))

    private fun Parameters.getByteVector32(argName: String): ByteVector32 = getString(argName).let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull() ?: invalidType(argName, "hex32") }

    private fun Parameters.getUUID(argName: String): UUID = getString(argName).let { uuid -> kotlin.runCatching { UUID.fromString(uuid) }.getOrNull() ?: invalidType(argName, "uuid") }

    private fun Parameters.getAddressAndConvertToScript(argName: String): ByteVector = Script.write(Bitcoin.addressToPublicKeyScript(nodeParams.chainHash, getString(argName)).right ?: error("invalid address")).toByteVector()

    private fun Parameters.getInvoice(argName: String): Bolt11Invoice = getString(argName).let { invoice -> Bolt11Invoice.read(invoice).getOrElse { invalidType(argName, "bolt11invoice") } }

    private fun Parameters.getLong(argName: String): Long = ((this[argName] ?: missing(argName)).toLongOrNull()) ?: invalidType(argName, "integer")

    private fun Parameters.getOptionalLong(argName: String): Long? = this[argName]?.let { it.toLongOrNull() ?: invalidType(argName, "integer") }

}
