package fr.acinq.lightning.bin.payments.lnurl.helpers

import fr.acinq.bitcoin.Bech32
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl.Request
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl.Tag
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlAuth
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlError
import io.ktor.http.*

/** Helper for parsing a string into an [Lnurl] object. */
object LnurlParser {

    private val prefixes = listOf("lightning://", "lightning:", "bitcoin://", "bitcoin:", "lnurl://", "lnurl:")

    /**
     * Remove the prefix from the input, if any. Trimming is done in a case-insensitive manner because often QR codes will
     * use upper-case for the prefix, such as LIGHTNING:LNURL1...
     */
    private fun trimPrefixes(
        input: String,
    ): String {
        val matchingPrefix = prefixes.firstOrNull { input.startsWith(it, ignoreCase = true) }
        return if (matchingPrefix != null) {
            input.drop(matchingPrefix.length)
        } else {
            input
        }
    }

    /**
     * Attempts to extract a [Lnurl] from a string.
     *
     * @param source can be a bech32 lnurl, a non-bech32 lnurl, or a lightning address.
     * @return a [LnurlAuth] if the source is a login lnurl, or an [Lnurl.Request] if it is a payment/withdrawal lnurl.
     *
     * Throws an exception if the source is malformed or invalid.
     */
    fun extractLnurl(source: String): Lnurl {
        val input = trimPrefixes(source)
        val url: Url = try {
            parseBech32Url(input)
        } catch (bech32Ex: Exception) {
            try {
                if (lud17Schemes.any { input.startsWith(it, ignoreCase = true) }) {
                    parseNonBech32Lud17(input)
                } else {
                    parseNonBech32Http(input)
                }
            } catch (nonBech32Ex: Exception) {
                throw LnurlError.Invalid.MalformedUrl(cause = nonBech32Ex)
            }
        }
        val tag = url.parameters["tag"]?.let {
            when (it) {
                Tag.Auth.label -> Tag.Auth
                Tag.Withdraw.label -> Tag.Withdraw
                Tag.Pay.label -> Tag.Pay
                else -> null // ignore unknown tags and handle the lnurl as a `request` to be executed immediately
            }
        }
        return when (tag) {
            Tag.Auth -> {
                val k1 = url.parameters["k1"]
                if (k1.isNullOrBlank()) {
                    throw LnurlError.Auth.MissingK1
                } else {
                    LnurlAuth(url, k1)
                }
            }
            else -> Request(url, tag)
        }
    }

    /** Lnurls are originally bech32 encoded. If unreadable, throw an exception. */
    private fun parseBech32Url(source: String): Url {
        val (hrp, data) = Bech32.decode(source)
        val payload = Bech32.five2eight(data, 0).decodeToString()
        val url = URLBuilder(payload).build()
        if (!url.protocol.isSecure()) throw LnurlError.Invalid.UnsafeResource
        return url
    }

    /** Lnurls sometimes hide in regular http urls, under the lightning parameter. */
    private fun parseNonBech32Http(source: String): Url {
        val urlBuilder = URLBuilder(source)
        val lightningParam = urlBuilder.parameters["lightning"]
        return if (!lightningParam.isNullOrBlank()) {
            // this url contains a lnurl fallback which takes priority - and must be bech32 encoded
            parseBech32Url(lightningParam)
        } else {
            if (!urlBuilder.protocol.isSecure()) throw LnurlError.Invalid.UnsafeResource
            urlBuilder.build()
        }
    }

    private val lud17Schemes = listOf(
        "phoenix:lnurlp://", "phoenix:lnurlp:",
        "lnurlp://", "lnurlp:",
        "phoenix:lnurlw://", "phoenix:lnurlw:",
        "lnurlw://", "lnurlw:",
        "phoenix:keyauth://", "phoenix:keyauth:",
        "keyauth://", "keyauth:",
    )

    /** Converts LUD-17 lnurls (using a custom scheme like lnurlc:, lnurlp:, keyauth:) into a regular http url. */
    private fun parseNonBech32Lud17(source: String): Url {
        val matchingPrefix = lud17Schemes.firstOrNull { source.startsWith(it, ignoreCase = true) }
        val stripped = if (matchingPrefix != null) {
            source.drop(matchingPrefix.length)
        } else {
            throw IllegalArgumentException("source does not use a lud17 scheme: $source")
        }
        return URLBuilder(stripped).apply {
            encodedPath.split("/", ignoreCase = true, limit = 2).let {
                this.host = it.first()
                this.encodedPath = "/${it.drop(1).joinToString()}"
            }
            protocol = if (this.host.endsWith(".onion")) {
                URLProtocol.HTTP
            } else {
                URLProtocol.HTTPS
            }
        }.build()
    }
}