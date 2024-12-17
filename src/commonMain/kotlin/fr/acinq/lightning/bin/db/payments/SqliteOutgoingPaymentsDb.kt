package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.UUID
import fr.acinq.phoenix.db.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteOutgoingPaymentsDb(private val database: PhoenixDatabase) : OutgoingPaymentsDb {
    override suspend fun addLightningOutgoingPaymentParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.outgoingPaymentsQueries.get(parentId).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(parts = payment.parts + parts)
                database.outgoingPaymentsQueries.update(
                    id = parentId,
                    data = payment1,
                    completed_at = null,
                )
            }
            parts.forEach { part ->
                database.outgoingPaymentsQueries.insertPartLink(part_id = part.id, parent_id = parentId)
            }
        }
    }

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (outgoingPayment) {
                    is LightningOutgoingPayment -> {
                        database.outgoingPaymentsQueries.insert(
                            id = outgoingPayment.id,
                            payment_hash = outgoingPayment.paymentHash,
                            tx_id = null,
                            created_at = outgoingPayment.createdAt,
                            completed_at = outgoingPayment.completedAt,
                            // outgoing lightning payments can fail, the sent_at timestamp is only set if the payment was success
                            sent_at = if (outgoingPayment.status is LightningOutgoingPayment.Status.Succeeded) outgoingPayment.completedAt else null,
                            data_ = outgoingPayment
                        )
                    }
                    is OnChainOutgoingPayment -> {
                        database.outgoingPaymentsQueries.insert(
                            id = outgoingPayment.id,
                            payment_hash = null,
                            tx_id = outgoingPayment.txId,
                            created_at = outgoingPayment.createdAt,
                            completed_at = outgoingPayment.completedAt,
                            sent_at = outgoingPayment.completedAt,
                            data_ = outgoingPayment
                        )
                        database.onChainTransactionsQueries.insert(
                            payment_id = outgoingPayment.id,
                            tx_id = outgoingPayment.txId
                        )
                    }
                }
            }
        }
    }

    override suspend fun completeLightningOutgoingPayment(id: UUID, status: LightningOutgoingPayment.Status.Completed) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.outgoingPaymentsQueries.get(id).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(status = status)
                database.outgoingPaymentsQueries.update(
                    id = id,
                    data = payment1,
                    completed_at = status.completedAt
                )
            }
        }
    }

    override suspend fun completeLightningOutgoingPaymentPart(parentId: UUID, partId: UUID, status: LightningOutgoingPayment.Part.Status.Completed) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.outgoingPaymentsQueries.get(parentId).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(parts = payment.parts.map {
                    when {
                        it.id == partId -> it.copy(status = status)
                        else -> it
                    }
                })
                database.outgoingPaymentsQueries.update(
                    id = parentId,
                    completed_at = status.completedAt,
                    data = payment1
                )
            }
        }
    }

    override suspend fun getInboundLiquidityPurchase(fundingTxId: TxId): InboundLiquidityOutgoingPayment? {
        return withContext(Dispatchers.Default) {
            database.outgoingPaymentsQueries.listByTxId(fundingTxId).executeAsList()
                .filterIsInstance<InboundLiquidityOutgoingPayment>()
                .firstOrNull()
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? {
        return withContext(Dispatchers.Default) {
            database.outgoingPaymentsQueries.get(id).executeAsOneOrNull() as? LightningOutgoingPayment
        }
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? {
        return database.transactionWithResult {
            val paymentId = database.outgoingPaymentsQueries.getParentId(partId).executeAsOneOrNull()!!
            database.outgoingPaymentsQueries.get(paymentId).executeAsOneOrNull() as? LightningOutgoingPayment
        }
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> {
        return withContext(Dispatchers.Default) {
            database.outgoingPaymentsQueries.listByPaymentHash(paymentHash).executeAsList().filterIsInstance<LightningOutgoingPayment>()
        }
    }
}