package fr.acinq.phoenixd.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import fr.acinq.phoenixd.deriveUUID
import io.ktor.http.*

class PaymentsMetadataQueries(private val database: PhoenixDatabase) {

    private val queries = database.paymentsMetadataQueries

    fun insert(paymentHash: ByteVector32, externalId: String?, webhookUrl: Url?) {
        queries.insert(payment_id = paymentHash.deriveUUID(), external_id = externalId, webhook_url = webhookUrl, created_at = currentTimestampMillis())
    }

    fun get(paymentHash: ByteVector32): PaymentMetadata? {
        return queries.get(paymentHash.deriveUUID(), mapper = Companion::mapper)
            .executeAsOneOrNull()
    }

    companion object {
        fun mapper(
            external_id: String?,
            webhook_url: Url?,
            created_at: Long,
        ): PaymentMetadata {
            return PaymentMetadata(externalId = external_id, webhookUrl = webhook_url, createdAt = created_at)
        }
    }
}