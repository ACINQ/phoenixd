package fr.acinq.lightning.bin.db.payments

import fr.acinq.lightning.bin.db.PaymentMetadata
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.db.PhoenixDatabase
import io.ktor.http.*

class PaymentsMetadataQueries(private val database: PhoenixDatabase) {

    private val queries = database.paymentsMetadataQueries

    fun get(walletPaymentId: WalletPaymentId): PaymentMetadata? {
        return queries.get(walletPaymentId.dbType.value, walletPaymentId.dbId, mapper = ::mapper)
            .executeAsOneOrNull()
    }

    fun insertExternalId(walletPaymentId: WalletPaymentId, externalId: String?, webhookUrl: Url?) {
        database.transaction {
            queries.insert(type = walletPaymentId.dbType.value, id = walletPaymentId.dbId, external_id = externalId, webhook_url =  webhookUrl.toString(), created_at = currentTimestampMillis())
        }
    }

    companion object {
        fun mapper(
            external_id: String?,
            webhook_url: String?,
            created_at: Long,
        ): PaymentMetadata {
            return PaymentMetadata(externalId = external_id, webhookUrl = webhook_url?.let { Url(it) }, createdAt = created_at)
        }
    }
}