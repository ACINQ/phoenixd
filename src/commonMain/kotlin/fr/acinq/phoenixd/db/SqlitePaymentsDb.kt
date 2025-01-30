/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenixd.db

import app.cash.sqldelight.db.QueryResult
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenixd.db.payments.PaymentsMetadataQueries
import fr.acinq.phoenixd.db.payments.SqliteIncomingPaymentsDb
import fr.acinq.phoenixd.db.payments.SqliteOutgoingPaymentsDb
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlitePaymentsDb(val database: PhoenixDatabase) :
    IncomingPaymentsDb by SqliteIncomingPaymentsDb(database),
    OutgoingPaymentsDb by SqliteOutgoingPaymentsDb(database),
    PaymentsDb {

    val metadataQueries = PaymentsMetadataQueries(database)

    override suspend fun getInboundLiquidityPurchase(txId: TxId): LiquidityAds.LiquidityTransactionDetails? {
        val payment = buildList {
            addAll(database.paymentsIncomingQueries.listByTxId(txId).executeAsList())
            addAll(database.paymentsOutgoingQueries.listByTxId(txId).executeAsList())
        }.firstOrNull()
        @Suppress("DEPRECATION")
        return when (payment) {
            is LightningIncomingPayment -> payment.liquidityPurchaseDetails
            is OnChainIncomingPayment -> payment.liquidityPurchaseDetails
            is LegacyPayToOpenIncomingPayment -> null
            is LegacySwapInIncomingPayment -> null
            is LightningOutgoingPayment -> null
            is OnChainOutgoingPayment -> payment.liquidityPurchaseDetails
            null -> null
        }
    }

    override suspend fun setLocked(txId: TxId) {
        database.transaction {
            val lockedAt = currentTimestampMillis()
            database.onChainTransactionsQueries.setLocked(tx_id = txId, locked_at = lockedAt)
            database.paymentsIncomingQueries.listByTxId(txId).executeAsList().filterIsInstance<OnChainIncomingPayment>().forEach { payment ->
                val payment1 = payment.setLocked(lockedAt)
                database.paymentsIncomingQueries.update(id = payment1.id, data = payment1, txId = payment1.txId, receivedAt = payment1.lockedAt)
            }
            database.paymentsOutgoingQueries.listByTxId(txId).executeAsList().filterIsInstance<OnChainOutgoingPayment>().forEach { payment ->
                val payment1 = payment.setLocked(lockedAt)
                database.paymentsOutgoingQueries.update(id = payment1.id, data = payment1, completed_at = payment1.completedAt, succeeded_at = payment1.succeededAt)
            }
        }
    }

    suspend fun setConfirmed(txId: TxId) = withContext(Dispatchers.Default) {
        database.transaction {
            val confirmedAt = currentTimestampMillis()
            database.onChainTransactionsQueries.setConfirmed(tx_id = txId, confirmed_at = confirmedAt)
            database.paymentsIncomingQueries.listByTxId(txId).executeAsList().filterIsInstance<OnChainIncomingPayment>().forEach { payment ->
                val payment1 = payment.setConfirmed(confirmedAt)
                // receivedAt must still set to lockedAt, and not confirmedAt.
                database.paymentsIncomingQueries.update(id = payment1.id, data = payment1, txId = payment1.txId, receivedAt = payment1.lockedAt)
            }
            database.paymentsOutgoingQueries.listByTxId(txId).executeAsList().filterIsInstance<OnChainOutgoingPayment>().forEach { payment ->
                val payment1 = payment.setConfirmed(confirmedAt)
                database.paymentsOutgoingQueries.update(id = payment1.id, data = payment1, completed_at = payment1.completedAt, succeeded_at = payment1.succeededAt)
            }
        }
    }

    /** Will return either [LightningIncomingPayment] or [LegacyPayToOpenIncomingPayment] (useful for backward compatibility). */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? =
        withContext(Dispatchers.Default) {
            database.paymentsIncomingQueries.getByPaymentHash(paymentHash).executeAsOneOrNull()
        }

    // ---- list payments with filter

    suspend fun listIncomingPayments(from: Long, to: Long, limit: Long, offset: Long, listAll: Boolean, externalId: String? = null): List<Pair<IncomingPayment, String?>> {
        return withContext(Dispatchers.Default) {
            if (listAll) {
                database.paymentsIncomingQueries.list(created_at_from = from, created_at_to = to, limit = limit, offset = offset, externalId = externalId) { data, externalId -> data to externalId }
                    .executeAsList()
            } else {
                database.paymentsIncomingQueries.listSuccessful(received_at_from = from, received_at_to = to, limit = limit, offset = offset, externalId = externalId) { data, externalId -> data to externalId }
                    .executeAsList()
            }
        }
    }

    suspend fun listOutgoingPayments(from: Long, to: Long, limit: Long, offset: Long, listAll: Boolean): List<OutgoingPayment> {
        return withContext(Dispatchers.Default) {
            if (listAll) {
                database.paymentsOutgoingQueries.list(created_at_from = from, created_at_to = to, limit = limit, offset = offset).executeAsList()
            } else {
                database.paymentsOutgoingQueries.listSuccessful(succeeded_at_from = from, succeeded_at_to = to, limit = limit, offset = offset).executeAsList()
            }
        }
    }

    suspend fun processSuccessfulPayments(from: Long, to: Long, batchSize: Long = 32, process: (WalletPayment) -> Unit) {
        return withContext(Dispatchers.Default) {
            var batchOffset = 0L
            var fetching = true
            while (fetching) {
                database.paymentsQueries.listSuccessful(succeeded_at_from = from, succeeded_at_to = to, limit = batchSize, offset = batchOffset)
                    .execute { cursor ->
                        var resultSize = 0
                        while (cursor.next().value) {
                            val data = cursor.getBytes(0)!!
                            val walletPayment = WalletPaymentAdapter.decode(data)
                            process(walletPayment)
                            resultSize += 1
                        }
                        if (resultSize == 0) {
                            fetching = false
                        } else {
                            batchOffset += resultSize
                        }
                        QueryResult.Unit
                    }
            }
        }
    }
}
