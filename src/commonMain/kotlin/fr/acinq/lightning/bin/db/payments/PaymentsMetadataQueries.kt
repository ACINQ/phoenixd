package fr.acinq.lightning.bin.db.payments

import fr.acinq.lightning.bin.db.PaymentMetadata
import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.db.PhoenixDatabase

class PaymentsMetadataQueries(private val database: PhoenixDatabase) {

    private val queries = database.paymentsMetadataQueries

    fun get(walletPaymentId: WalletPaymentId): PaymentMetadata? {
        return queries.get(walletPaymentId.dbType.value, walletPaymentId.dbId, mapper = ::mapper)
            .executeAsOneOrNull()
    }

    fun getByExternalId(id: String): List<Pair<WalletPaymentId, PaymentMetadata>> {
        return queries.getByExternalId(id).executeAsList().mapNotNull { res ->
            WalletPaymentId.create(type = res.type, id = res.id)?.let { it to mapper(res.external_id, res.created_at) }
        }
    }

    fun insertExternalId(walletPaymentId: WalletPaymentId, id: String) {
        database.transaction {
            queries.insert(type = walletPaymentId.dbType.value, id = walletPaymentId.dbId, external_id = id, created_at = currentTimestampMillis())
        }
    }

    companion object {
        fun mapper(
            external_id: String?,
            created_at: Long,
        ): PaymentMetadata {
            return PaymentMetadata(externalId = external_id, createdAt = created_at)
        }
    }
}