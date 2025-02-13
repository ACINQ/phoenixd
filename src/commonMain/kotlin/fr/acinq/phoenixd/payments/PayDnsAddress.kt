package fr.acinq.phoenixd.payments

import fr.acinq.lightning.wire.OfferTypes
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.charsets.*
import kotlinx.serialization.json.*

class PayDnsAddress {

    private val httpClient: HttpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(json = Json { ignoreUnknownKeys = true })
            }
            expectSuccess = false
        }
    }

    /**
     * Resolves dns-based offers.
     * See https://github.com/bitcoin/bips/blob/master/bip-0353.mediawiki.
     */
    suspend fun resolveBip353Offer(
        username: String,
        domain: String,
    ): OfferTypes.Offer? {

        val dnsPath = "$username.user._bitcoin-payment.$domain."

        // list of resolvers: https://dnsprivacy.org/public_resolvers/
        val url = Url("https://dns.google/resolve?name=$dnsPath&type=TXT")

        try {
            val response = httpClient.get(url)
            val json = Json.decodeFromString<JsonObject>(response.bodyAsText(Charsets.UTF_8))
            val status = json["Status"]?.jsonPrimitive?.intOrNull
            if (status == null || status > 0) throw RuntimeException("invalid status=$status")

            val ad = json["AD"]?.jsonPrimitive?.booleanOrNull
            if (ad != true) {
                return null
            }

            val records = json["Answer"]?.jsonArray
            if (records.isNullOrEmpty()) {
                return null
            }

            val matchingRecord = records.filterIsInstance<JsonObject>().firstOrNull {
                it["name"]?.jsonPrimitive?.content == dnsPath
            } ?: return null

            val data = matchingRecord["data"]?.jsonPrimitive?.content ?: return null
            return Parser.parseBip21Offer(data)
        } catch (e: Exception) {
            return null
        }
    }
}