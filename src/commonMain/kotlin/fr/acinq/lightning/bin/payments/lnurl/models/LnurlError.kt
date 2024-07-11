package fr.acinq.lightning.bin.payments.lnurl.models

import io.ktor.http.*

sealed class LnurlError(override val message: String? = null) : RuntimeException(message) {
    val details: String by lazy { "Lnurl error=${message ?: this::class.simpleName ?: "N/A"}" }

    sealed class Invalid(override val message: String) : LnurlError() {
        data class MalformedUrl(override val cause: Throwable?) : Invalid("cannot be parsed as a bech32 or as a human readable lnurl")
        data object NoTag : Invalid("no tag field found")
        data class UnhandledTag(val tag: String) : Invalid("unhandled tag=$tag")
        data object UnsafeResource : Invalid("resource should be https")
        data object MissingCallback : Invalid("missing callback in metadata response")
    }

    sealed class RemoteFailure(override val message: String) : LnurlError(message) {
        abstract val origin: String

        data class CouldNotConnect(override val origin: String) : RemoteFailure("could not connect to $origin")
        data class Unreadable(override val origin: String) : RemoteFailure("unreadable response from $origin")
        data class Detailed(override val origin: String, val reason: String) : RemoteFailure("error=$reason from $origin")
        data class Code(override val origin: String, val code: HttpStatusCode) : RemoteFailure("error code=$code from $origin")
    }

    sealed class Auth(override val message: String?) : LnurlError(message) {
        data object MissingK1 : Auth("missing k1 parameter")
    }

    sealed class Withdraw(override val message: String?) : LnurlError(message) {
        data object MissingK1 : Withdraw("missing k1 parameter")
    }

    sealed class Pay : LnurlError() {
        sealed class BadParameters(override val message: String?) : LnurlError(message) {
            data object InvalidMin : BadParameters("invalid minimum amount")
            data object MissingMax : BadParameters("missing maximum amount parameter")
            data object MissingMetadata : BadParameters("missing metadata parameter")
            data class InvalidMetadata(val meta: String) : BadParameters("invalid metadata=$meta")
        }

        sealed class BadInvoice(override val message: String?) : LnurlError(message) {
            abstract val origin: String

            data class Malformed(
                override val origin: String,
                val context: String
            ) : BadInvoice("malformed invoice: $context")

            data class InvalidAmount(override val origin: String) : BadInvoice("invoice's amount doesn't match input")
        }
    }
}
