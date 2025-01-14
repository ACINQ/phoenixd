package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.*
import fr.acinq.phoenix.db.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteIncomingPaymentsDb(private val database: PhoenixDatabase) : IncomingPaymentsDb {

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) {
        return withContext(Dispatchers.Default) {
            database.transaction {
                database.paymentsIncomingQueries.insert(
                    id = incomingPayment.id,
                    payment_hash = (incomingPayment as? LightningIncomingPayment)?.paymentHash,
                    tx_id = (incomingPayment as? OnChainIncomingPayment)?.txId,
                    created_at = incomingPayment.createdAt,
                    received_at = incomingPayment.completedAt,
                    data_ = incomingPayment
                )
                // if the payment is on-chain, save the tx id link to the db
                when (incomingPayment) {
                    is OnChainIncomingPayment ->
                        database.onChainTransactionsQueries.insert(
                            payment_id = incomingPayment.id,
                            tx_id = incomingPayment.txId
                        )
                    else -> {}
                }
            }
        }
    }

    override suspend fun getLightningIncomingPayment(paymentHash: ByteVector32): LightningIncomingPayment? =
        withContext(Dispatchers.Default) {
            database.paymentsIncomingQueries.getByPaymentHash(paymentHash).executeAsOneOrNull() as? LightningIncomingPayment
        }

    override suspend fun receiveLightningPayment(paymentHash: ByteVector32, parts: List<LightningIncomingPayment.Part>) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (val paymentInDb = database.paymentsIncomingQueries.getByPaymentHash(paymentHash).executeAsOneOrNull() as? LightningIncomingPayment) {
                    is LightningIncomingPayment -> {
                        val paymentInDb1 = paymentInDb.addReceivedParts(parts)
                        database.paymentsIncomingQueries.update(
                            id = paymentInDb1.id,
                            data = paymentInDb1,
                            receivedAt = paymentInDb1.completedAt
                        )
                    }
                    null -> error("missing payment for payment_hash=$paymentHash")
                }
            }
        }
    }

    override suspend fun listLightningExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> =
        withContext(Dispatchers.Default) {
            database.paymentsIncomingQueries.list(created_at_from = fromCreatedAt, created_at_to = toCreatedAt, externalId = null, offset = 0, limit = Long.MAX_VALUE)
                .executeAsList()
                .filterIsInstance<Bolt11IncomingPayment>()
                .filter { it.parts.isEmpty() && it.paymentRequest.isExpired() }
        }

    override suspend fun removeLightningIncomingPayment(paymentHash: ByteVector32): Boolean =
        withContext(Dispatchers.Default) {
            database.transactionWithResult {
                database.paymentsIncomingQueries.delete(payment_hash = paymentHash)
                database.paymentsIncomingQueries.changes().executeAsOne() != 0L
            }
        }
}