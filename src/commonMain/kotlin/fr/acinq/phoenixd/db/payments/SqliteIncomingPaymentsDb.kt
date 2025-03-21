package fr.acinq.phoenixd.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.*
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteIncomingPaymentsDb(private val database: PhoenixDatabase) : IncomingPaymentsDb {

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) {
        return withContext(Dispatchers.Default) {
            database.transaction {
                database.paymentsIncomingQueries.insert(
                    id = incomingPayment.id,
                    payment_hash = (incomingPayment as? LightningIncomingPayment)?.paymentHash,
                    tx_id = when (incomingPayment) {
                        is LightningIncomingPayment -> incomingPayment.liquidityPurchaseDetails?.txId
                        is OnChainIncomingPayment -> incomingPayment.txId
                        else -> null
                    },
                    created_at = incomingPayment.createdAt,
                    received_at = incomingPayment.completedAt,
                    data_ = incomingPayment
                )
                // if the payment is on-chain, save the tx id link to the db
                when (incomingPayment) {
                    is OnChainIncomingPayment ->
                        database.onChainTransactionsQueries.insert(
                            payment_id = incomingPayment.id,
                            tx_id = incomingPayment.txId,
                            confirmed_at = incomingPayment.confirmedAt,
                            locked_at = incomingPayment.lockedAt
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

    override suspend fun receiveLightningPayment(paymentHash: ByteVector32, parts: List<LightningIncomingPayment.Part>, liquidityPurchase: LiquidityAds.LiquidityTransactionDetails?) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (val paymentInDb = database.paymentsIncomingQueries.getByPaymentHash(paymentHash).executeAsOneOrNull() as? LightningIncomingPayment) {
                    is LightningIncomingPayment -> {
                        val paymentInDb1 = paymentInDb.addReceivedParts(parts, liquidityPurchase)
                        database.paymentsIncomingQueries.update(
                            id = paymentInDb1.id,
                            data = paymentInDb1,
                            receivedAt = paymentInDb1.completedAt,
                            txId = paymentInDb1.liquidityPurchaseDetails?.txId
                        )
                        liquidityPurchase?.let {
                            when (val autoLiquidityPayment = database.paymentsOutgoingQueries.listByTxId(liquidityPurchase.txId).executeAsOneOrNull()) {
                                is AutomaticLiquidityPurchasePayment -> {
                                    val autoLiquidityPayment1 = autoLiquidityPayment.copy(incomingPaymentReceivedAt = paymentInDb1.completedAt)
                                    database.paymentsOutgoingQueries.update(
                                        id = autoLiquidityPayment1.id,
                                        completed_at = autoLiquidityPayment1.completedAt,
                                        succeeded_at = autoLiquidityPayment1.succeededAt,
                                        data = autoLiquidityPayment1
                                    )
                                }
                                else -> {}
                            }
                        }
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
                database.paymentsIncomingQueries.deleteByPaymentHash(payment_hash = paymentHash)
                database.paymentsIncomingQueries.changes().executeAsOne() != 0L
            }
        }
}