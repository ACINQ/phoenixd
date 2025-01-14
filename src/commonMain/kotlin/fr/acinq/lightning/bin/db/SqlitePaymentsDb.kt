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

package fr.acinq.lightning.bin.db

import app.cash.sqldelight.db.QueryResult
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.bin.db.payments.PaymentsMetadataQueries
import fr.acinq.lightning.bin.db.payments.SqliteIncomingPaymentsDb
import fr.acinq.lightning.bin.db.payments.SqliteOutgoingPaymentsDb
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.db.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlitePaymentsDb(val database: PhoenixDatabase) :
    IncomingPaymentsDb by SqliteIncomingPaymentsDb(database),
    OutgoingPaymentsDb by SqliteOutgoingPaymentsDb(database),
    PaymentsDb {

    val metadataQueries = PaymentsMetadataQueries(database)

    override suspend fun setLocked(txId: TxId) {
        database.transaction {
            val lockedAt = currentTimestampMillis()
            database.onChainTransactionsQueries.setLocked(tx_id = txId, locked_at = lockedAt)
            database.onChainTransactionsQueries
                .listByTxId(txId)
                .executeAsList()
                .map { WalletPaymentAdapter.decode(it) }
                .forEach { payment ->
                    @Suppress("DEPRECATION")
                    when (payment) {
                        is LightningIncomingPayment -> {}
                        is OnChainIncomingPayment -> {
                            val payment1 = payment.setLocked(lockedAt)
                            database.paymentsIncomingQueries.update(id = payment1.id, data = payment1, receivedAt = lockedAt)
                        }
                        is LegacyPayToOpenIncomingPayment -> {}
                        is LegacySwapInIncomingPayment -> {}
                        is LightningOutgoingPayment -> {}
                        is OnChainOutgoingPayment -> {
                            val payment1 = payment.setLocked(lockedAt)
                            database.paymentsOutgoingQueries.update(id = payment1.id, data = payment1, completed_at = lockedAt, sent_at = lockedAt)
                        }
                    }
                }
        }
    }

    suspend fun setConfirmed(txId: TxId) = withContext(Dispatchers.Default) {
        database.transaction {
            val confirmedAt = currentTimestampMillis()
            database.onChainTransactionsQueries.setConfirmed(tx_id = txId, confirmed_at = confirmedAt)
            database.onChainTransactionsQueries
                .listByTxId(txId)
                .executeAsList()
                .map { WalletPaymentAdapter.decode(it) }
                .forEach { payment ->
                    @Suppress("DEPRECATION")
                    when (payment) {
                        is LightningIncomingPayment -> {}
                        is OnChainIncomingPayment -> {
                            val payment1 = payment.setConfirmed(confirmedAt)
                            database.paymentsIncomingQueries.update(id = payment1.id, data = payment1, receivedAt = null)
                        }
                        is LegacyPayToOpenIncomingPayment -> {}
                        is LegacySwapInIncomingPayment -> {}
                        is LightningOutgoingPayment -> {}
                        is OnChainOutgoingPayment -> {
                            val payment1 = payment.setConfirmed(confirmedAt)
                            database.paymentsOutgoingQueries.update(id = payment1.id, data = payment1, completed_at = null, sent_at = null)
                        }
                    }
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
                database.paymentsOutgoingQueries.listSuccessful(sent_at_from = from, sent_at_to = to, limit = limit, offset = offset).executeAsList()
            }
        }
    }

    suspend fun listSuccessfulPayments(from: Long = 0, to: Long = Long.MAX_VALUE, limit: Long = Long.MAX_VALUE, offset: Long = 0): List<WalletPayment> {
        return withContext(Dispatchers.Default) {
            database.paymentsQueries.list(
                completed_at_from = from,
                completed_at_to = to,
                limit = limit,
                offset = offset
            )
                .executeAsList()
                .map { WalletPaymentAdapter.decode(it) }
        }
    }

    suspend fun processSuccessfulPayments(from: Long, to: Long, batchSize: Long = 32, process: (WalletPayment) -> Unit) {
        return withContext(Dispatchers.Default) {
            var batchOffset = 0L
            var fetching = true
            while (fetching) {
                database.paymentsQueries.list(completed_at_from = from, completed_at_to = to, limit = batchSize, offset = batchOffset)
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
