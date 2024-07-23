package fr.acinq.lightning.bin.payments

import fr.acinq.bitcoin.utils.Try
import fr.acinq.lightning.wire.OfferTypes
import io.ktor.http.*

object Parser {
    fun parseEmailLikeAddress(input: String): Pair<String, String>? {
        if (!input.contains("@", ignoreCase = true)) return null

        // Ignore excess input, including additional lines, and leading/trailing whitespace
        val line = input.lines().firstOrNull { it.isNotBlank() }?.trim()
        val token = line?.split("\\s+".toRegex())?.firstOrNull()

        if (token.isNullOrBlank()) return null

        val components = token.split("@")
        if (components.size != 2) {
            return null
        }

        val username = components[0].lowercase().dropWhile { it == '₿' }
        val domain = components[1]
        return username to domain
    }

    fun parseBip21Offer(uri: String): OfferTypes.Offer? {
        val url = Url(uri)
        if (url.protocol.name != "bitcoin") return null
        return url.parameters["lno"]?.let {
            when (val offer = OfferTypes.Offer.decode(it)) {
                is Try.Success -> offer.result
                is Try.Failure -> null
            }
        }
    }
}