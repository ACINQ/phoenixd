package fr.acinq.lightning.bin.payments.lnurl.models

import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.http.*
import kotlinx.serialization.json.*

sealed class LnurlPay : Lnurl.Qualified {

    /**
     * Response from a lnurl service to describe what kind of payment is expected.
     * First step of the lnurl-pay flow.
     */
    data class PaymentParameters(
        override val initialUrl: Url,
        val callback: Url,
        val minSendable: MilliSatoshi,
        val maxSendable: MilliSatoshi,
        val metadata: Metadata,
        val maxCommentLength: Long?
    ) : LnurlPay() {
        data class Metadata(
            val raw: String,
            val plainText: String,
            val longDesc: String?,
            val imagePng: String?, // base64 encoded png
            val imageJpg: String?, // base64 encoded jpg
            val identifier: String?,
            val email: String?,
            val unknown: JsonArray?
        ) {
            val lnid: String? by lazy { email ?: identifier }

            override fun toString(): String {
                return "Metadata(plainText=$plainText, longDesc=${longDesc?.take(50)}, identifier=$identifier, email=$email, imagePng=${imagePng?.take(10)}, imageJpg=${imageJpg?.take(10)})"
            }
        }

        override fun toString(): String {
            return "PaymentParameters(minSendable=$minSendable, maxSendable=$maxSendable, metadata=$metadata, maxCommentLength=$maxCommentLength, initialUrl=$initialUrl, callback=$callback)".take(100)
        }
    }

    /**
     * Invoice returned by a lnurl service after user states what they want to pay.
     * Second step of the lnurl-payment flow.
     */
    data class InvoiceToPay(
        override val initialUrl: Url,
        val invoice: Bolt11Invoice,
        val successAction: SuccessAction?
    ) : LnurlPay() {
        sealed class SuccessAction {
            data class Message(
                val message: String
            ) : SuccessAction()

            data class Url(
                val description: String,
                val url: io.ktor.http.Url
            ) : SuccessAction()

            data class Aes(
                val description: String,
                val ciphertext: ByteVector,
                val iv: ByteVector
            ) : SuccessAction() {
                data class Decrypted(
                    val description: String,
                    val plaintext: String
                )
            }

            enum class Tag(val label: String) {
                Message("message"),
                Url("url"),
                Aes("aes")
            }
        }
    }
}


