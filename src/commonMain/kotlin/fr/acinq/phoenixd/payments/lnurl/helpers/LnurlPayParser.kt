package fr.acinq.phoenixd.payments.lnurl.helpers

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.utils.Try
import fr.acinq.phoenixd.payments.lnurl.models.Lnurl
import fr.acinq.phoenixd.payments.lnurl.models.LnurlError
import fr.acinq.phoenixd.payments.lnurl.models.LnurlPay
import fr.acinq.phoenixd.payments.lnurl.models.LnurlPay.PaymentParameters
import fr.acinq.phoenixd.payments.lnurl.models.LnurlPay.InvoiceToPay
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.json.*

/** Parsers specific to lnurl-pay. */
object LnurlPayParser {

    /** Unknown elements in the json returned by the lnurl-pay service must be ignored. */
    private val format: Json = Json { ignoreUnknownKeys = true }

    /** Parses json into a [LnurlPay.InvoiceToPay] object. Throws an [LnurlError.Pay.BadInvoice] exception if unreadable. */
    fun parseInvoiceToPay(
        intent: PaymentParameters,
        origin: String,
        json: JsonObject
    ): InvoiceToPay {
        try {
            val pr = json["pr"]?.jsonPrimitive?.content ?: throw LnurlError.Pay.BadInvoice.Malformed(origin, "missing invoice parameter")
            val invoice = when (val res = Bolt11Invoice.read(pr)) {
                is Try.Success -> res.result
                is Try.Failure -> throw LnurlError.Pay.BadInvoice.Malformed(origin, "$pr [${res.error.message ?: res.error::class.toString()}]")
            }

            val successAction = parseSuccessAction(origin, json)
            return InvoiceToPay(intent.initialUrl, invoice, successAction)
        } catch (t: Throwable) {
            when (t) {
                is LnurlError.Pay.BadInvoice -> throw t
                else -> throw LnurlError.Pay.BadInvoice.Malformed(origin, "unknown error")
            }
        }
    }

    private fun parseSuccessAction(
        origin: String,
        json: JsonObject
    ): InvoiceToPay.SuccessAction? {
        val obj = try {
            json["successAction"]?.jsonObject // throws on Non-JsonObject (e.g. JsonNull)
        } catch (t: Throwable) {
            null
        } ?: return null

        return when (obj["tag"]?.jsonPrimitive?.content) {
            InvoiceToPay.SuccessAction.Tag.Message.label -> {
                val message = obj["message"]?.jsonPrimitive?.content ?: return null
                if (message.isBlank() || message.length > 144) {
                    throw LnurlError.Pay.BadInvoice.Malformed(origin, "success.message: bad length")
                }
                InvoiceToPay.SuccessAction.Message(message)
            }
            InvoiceToPay.SuccessAction.Tag.Url.label -> {
                val description = obj["description"]?.jsonPrimitive?.content ?: return null
                if (description.length > 144) {
                    throw LnurlError.Pay.BadInvoice.Malformed(origin, "success.url.description: bad length")
                }
                val urlStr = obj["url"]?.jsonPrimitive?.content ?: return null
                val url = Url(urlStr)
                InvoiceToPay.SuccessAction.Url(description, url)
            }
            InvoiceToPay.SuccessAction.Tag.Aes.label -> {
                val description = obj["description"]?.jsonPrimitive?.content ?: return null
                if (description.length > 144) {
                    throw LnurlError.Pay.BadInvoice.Malformed(origin, "success.aes.description: bad length")
                }
                val ciphertextStr = obj["ciphertext"]?.jsonPrimitive?.content ?: return null
                val ciphertext = ByteVector(ciphertextStr.decodeBase64Bytes())
                if (ciphertext.size() > (4 * 1024)) {
                    throw LnurlError.Pay.BadInvoice.Malformed(origin, "success.aes.ciphertext: bad length")
                }
                val ivStr = obj["iv"]?.jsonPrimitive?.content ?: return null
                if (ivStr.length != 24) {
                    throw LnurlError.Pay.BadInvoice.Malformed(origin, "success.aes.iv: bad length")
                }
                val iv = ByteVector(ivStr.decodeBase64Bytes())
                InvoiceToPay.SuccessAction.Aes(description, ciphertext = ciphertext, iv = iv)
            }
            else -> null
        }
    }

    /** Decode a serialized [Lnurl.Pay.Metadata] object. */
    fun parseMetadata(raw: String): PaymentParameters.Metadata {
        return try {
            val array = format.decodeFromString<JsonArray>(raw)
            var plainText: String? = null
            var longDesc: String? = null
            var imagePng: String? = null
            var imageJpg: String? = null
            var identifier: String? = null
            var email: String? = null
            val unknown = mutableListOf<JsonElement>()
            array.forEach {
                try {
                    when (it.jsonArray[0].jsonPrimitive.content) {
                        "text/plain" -> plainText = it.jsonArray[1].jsonPrimitive.content
                        "text/long-desc" -> longDesc = it.jsonArray[1].jsonPrimitive.content
                        "image/png;base64" -> imagePng = it.jsonArray[1].jsonPrimitive.content
                        "image/jpeg;base64" -> imageJpg = it.jsonArray[1].jsonPrimitive.content
                        "text/identifier" -> identifier = it.jsonArray[1].jsonPrimitive.content
                        "text/email" -> email = it.jsonArray[1].jsonPrimitive.content
                        else -> unknown.add(it)
                    }
                } catch (e: Exception) {
                    Logger.w("LnurlPay") { "could not decode raw lnurlpay-meta=$it: ${e.message}" }
                }
            }
            PaymentParameters.Metadata(
                raw = raw,
                plainText = plainText!!,
                longDesc = longDesc,
                imagePng = imagePng,
                imageJpg = imageJpg,
                identifier = identifier,
                email = email,
                unknown = unknown.takeIf { it.isNotEmpty() }?.let {
                    JsonArray(it.toList())
                }
            )
        } catch (e: Exception) {
            Logger.e("LnurlPay") { "could not decode raw lnurlpay-meta=$raw: ${e.message}" }
            throw LnurlError.Pay.BadParameters.InvalidMetadata(raw)
        }
    }
}
