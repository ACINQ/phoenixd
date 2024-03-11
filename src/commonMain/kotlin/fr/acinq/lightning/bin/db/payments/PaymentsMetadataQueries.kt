package fr.acinq.lightning.bin.db.payments

import fr.acinq.lightning.bin.db.PaymentMetadata
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.phoenix.db.PaymentsDatabase

class PaymentsMetadataQueries(private val database: PaymentsDatabase) {

    private val queries = database.paymentsMetadataQueries

    fun get(walletPaymentId: WalletPaymentId): PaymentMetadata? {
        return queries.get(walletPaymentId.dbType.value, walletPaymentId.dbId, mapper = ::mapper)
            .executeAsOneOrNull()
    }

    companion object {
        fun mapper(
            type: Long,
            id: String,
            external_id: String?,
            created_at: Long,
        ): PaymentMetadata {
            return PaymentMetadata(externalId = external_id, createdAt = created_at)
        }
    }
}