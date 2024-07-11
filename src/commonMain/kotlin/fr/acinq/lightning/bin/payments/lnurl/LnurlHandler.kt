package fr.acinq.lightning.bin.payments.lnurl

import co.touchlab.kermit.Logger
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.payments.lnurl.helpers.LnurlPayParser
import fr.acinq.lightning.bin.payments.lnurl.helpers.LnurlAuthSigner
import fr.acinq.lightning.bin.payments.lnurl.models.*
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl.Tag
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.debug
import fr.acinq.lightning.logging.error
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.utils.msat
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.charsets.*
import kotlinx.serialization.json.*

/**
 * Executes and processes Lnurls into actionable objects.
 *
 * First step is to parse/execute an url and get more information, depending on the [Lnurl.Tag].
 *
 * Then depending on the type:
 * - lnurl-pay: query the service based on the parameters provided to obtain a bolt11 invoice
 * - lnurl-withdraw: query the service with an invoice we generated based on the parameters they provided, and wait to be paid.
 * - lnurl-auth: sign a k1 secret with our key (derived) and query the service with that sig/pubkey
 */
class LnurlHandler(
    loggerFactory: LoggerFactory,
    private val keyManager: LocalKeyManager
) {
    private val log = loggerFactory.newLogger(this::class)

    // We don't want ktor to break when receiving non-2xx response
    private val httpClient: HttpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(json = Json { ignoreUnknownKeys = true })
                expectSuccess = false
            }
        }
    }

    /** Executes an HTTP GET request on the provided url and parses the JSON response into an [Lnurl.Qualified] object. */
    suspend fun executeLnurl(url: Url): Lnurl.Qualified {
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (err: Throwable) {
            throw LnurlError.RemoteFailure.CouldNotConnect(origin = url.host)
        }
        try {
            val json = processHttpResponse(response, log)
            return parseLnurlJson(url, json)
        } catch (e: Exception) {
            when (e) {
                is LnurlError -> throw e
                else -> throw LnurlError.RemoteFailure.Unreadable(url.host)
            }
        }
    }

    /**
     * Execute an HTTP GET request to obtain a [LnurlPay.InvoiceToPay] from a [LnurlPay.PaymentParameters]. May throw a
     * [LnurlError.RemoteFailure] or a [LnurlError.Pay.BadInvoice] error.
     *
     * @param payParameters the description of the payment as provided by the service.
     * @param amount the amount that the user is willing to pay to settle the [LnurlPay.Intent].
     * @param comment an optional string commenting the payment and sent to the service.
     */
    suspend fun getLnurlPayInvoice(
        payParameters: LnurlPay.PaymentParameters,
        amount: MilliSatoshi,
        comment: String?
    ): LnurlPay.InvoiceToPay {

        val builder = URLBuilder(payParameters.callback)
        builder.parameters.append(name = "amount", value = amount.msat.toString())
        if (!comment.isNullOrEmpty()) {
            builder.parameters.append(name = "comment", value = comment)
        }
        val callback = builder.build()
        val origin = callback.host

        val response: HttpResponse = try {
            httpClient.get(callback)
        } catch (err: Throwable) {
            throw LnurlError.RemoteFailure.CouldNotConnect(origin)
        }

        val json = processHttpResponse(response, log)
        val invoice = LnurlPayParser.parseInvoiceToPay(payParameters, origin, json)

        // SPECS: LN WALLET verifies that the amount in the provided invoice equals the amount previously specified by user.
        if (amount != invoice.invoice.amount) {
            log.error { "rejecting invoice from $origin with amount_invoice=${invoice.invoice.amount} requested_amount=$amount" }
            throw LnurlError.Pay.BadInvoice.InvalidAmount(origin)
        }

        return invoice
    }

    /**
     * Send an invoice to a lnurl service following a [LnurlWithdraw] request.
     * Throw [LnurlError.RemoteFailure].
     */
    suspend fun sendWithdrawInvoice(
        lnurlWithdraw: LnurlWithdraw,
        paymentRequest: PaymentRequest
    ): JsonObject {

        val builder = URLBuilder(lnurlWithdraw.callback)
        builder.parameters.append(name = "k1", value = lnurlWithdraw.k1)
        builder.parameters.append(name = "pr", value = paymentRequest.write())
        val callback = builder.build()
        val origin = callback.host

        val response: HttpResponse = try {
            httpClient.get(callback)
        } catch (err: Throwable) {
            throw LnurlError.RemoteFailure.CouldNotConnect(origin)
        }

        // SPECS: even if the response is an error, the invoice may still be paid by the service
        // we still parse the response to see what's up.
        return processHttpResponse(response, log)
    }

    suspend fun signAndSendAuthRequest(
        auth: LnurlAuth,
    ) {
        val key = LnurlAuthSigner.getAuthLinkingKey(
            localKeyManager = keyManager,
            serviceUrl = auth.initialUrl,
        )
        val (pubkey, signedK1) = LnurlAuthSigner.signChallenge(auth.k1, key)

        val builder = URLBuilder(auth.initialUrl)
        builder.parameters.append(name = "sig", value = signedK1.toHex())
        builder.parameters.append(name = "key", value = pubkey.toString())
        val url = builder.build()

        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            throw LnurlError.RemoteFailure.CouldNotConnect(origin = url.host)
        }

        processHttpResponse(response, log) // throws on any/all non-success
    }

    /**
     * Processes an HTTP response from a lnurl service and returns a [JsonObject].
     *
     * Throw:
     * - [LnurlError.RemoteFailure.Code] if service returns a non-2XX code
     * - [LnurlError.RemoteFailure.Unreadable] if response is not valid JSON
     * - [LnurlError.RemoteFailure.Detailed] if service reports an internal error message (`{ status: "error", reason: "..." }`)
     */
    suspend fun processHttpResponse(response: HttpResponse, logger: Logger): JsonObject {
        val url = response.request.url
        val json: JsonObject = try {
            // From the LUD-01 specs:
            // > HTTP Status Codes and Content-Type:
            // > Neither status codes or any HTTP Header has any meaning. Servers may use
            // > whatever they want. Clients should ignore them [...] and just parse the
            // > response body as JSON, then interpret it accordingly.
            Json.decodeFromString(response.bodyAsText(Charsets.UTF_8))
        } catch (e: Exception) {
            logger.error(e) { "unhandled response from url=$url: " }
            throw LnurlError.RemoteFailure.Unreadable(url.host)
        }

        logger.debug { "lnurl service=${url.host} returned response=${json.toString().take(100)}" }
        return if (json["status"]?.jsonPrimitive?.content?.trim()?.equals("error", true) == true) {
            val errorMessage = json["reason"]?.jsonPrimitive?.content?.trim() ?: ""
            if (errorMessage.isNotEmpty()) {
                logger.error { "lnurl service=${url.host} returned error=$errorMessage" }
                throw LnurlError.RemoteFailure.Detailed(url.host, errorMessage.take(90).replace("<", ""))
            } else if (!response.status.isSuccess()) {
                throw LnurlError.RemoteFailure.Code(url.host, response.status)
            } else {
                throw LnurlError.RemoteFailure.Unreadable(url.host)
            }
        } else {
            json
        }
    }

    /** Converts a lnurl JSON response to a [Lnurl.Qualified] object. */
    fun parseLnurlJson(url: Url, json: JsonObject): Lnurl.Qualified {
        val callback = URLBuilder(json["callback"]?.jsonPrimitive?.content ?: throw LnurlError.Invalid.MissingCallback).build()
        if (!callback.protocol.isSecure()) throw LnurlError.Invalid.UnsafeResource
        val tag = json["tag"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw LnurlError.Invalid.NoTag
        return when (tag) {
            Tag.Withdraw.label -> {
                val k1 = json["k1"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: throw LnurlError.Withdraw.MissingK1
                val minWithdrawable = json["minWithdrawable"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0f }?.toLong()?.msat
                    ?: json["minWithdrawable"]?.jsonPrimitive?.long?.takeIf { it > 0 }?.msat
                    ?: 0.msat
                val maxWithdrawable = json["maxWithdrawable"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0f }?.toLong()?.msat
                    ?: json["maxWithdrawable"]?.jsonPrimitive?.long?.takeIf { it > 0 }?.msat
                    ?: minWithdrawable
                val dDesc = json["defaultDescription"]?.jsonPrimitive?.content ?: ""
                LnurlWithdraw(
                    initialUrl = url,
                    callback = callback,
                    k1 = k1,
                    defaultDescription = dDesc,
                    minWithdrawable = minWithdrawable.coerceAtMost(maxWithdrawable),
                    maxWithdrawable = maxWithdrawable
                )
            }
            Tag.Pay.label -> {
                val minSendable = json["minSendable"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0f }?.toLong()?.msat
                    ?: json["minSendable"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.msat
                    ?: throw LnurlError.Pay.BadParameters.InvalidMin
                val maxSendable = json["maxSendable"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0f }?.toLong()?.msat
                    ?: json["maxSendable"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(minSendable.msat)?.msat
                    ?: throw LnurlError.Pay.BadParameters.MissingMax
                val metadata = LnurlPayParser.parseMetadata(json["metadata"]?.jsonPrimitive?.content ?: throw LnurlError.Pay.BadParameters.MissingMetadata)
                val maxCommentLength = json["commentAllowed"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                LnurlPay.PaymentParameters(
                    initialUrl = url,
                    callback = callback,
                    minSendable = minSendable,
                    maxSendable = maxSendable,
                    metadata = metadata,
                    maxCommentLength = maxCommentLength
                )
            }
            else -> throw LnurlError.Invalid.UnhandledTag(tag)
        }
    }
}