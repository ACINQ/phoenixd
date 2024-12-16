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
import fr.acinq.lightning.bin.db.payments.IncomingQueries
import fr.acinq.lightning.bin.db.payments.PaymentsMetadataQueries
import fr.acinq.lightning.db.*
import fr.acinq.lightning.db.OnChainIncomingPayment.Companion.setConfirmed
import fr.acinq.lightning.db.OnChainIncomingPayment.Companion.setLocked
import fr.acinq.lightning.db.OnChainOutgoingPayment.Companion.setConfirmed
import fr.acinq.lightning.db.OnChainOutgoingPayment.Companion.setLocked
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.db.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlitePaymentsDb(val database: PhoenixDatabase) : PaymentsDb {

    private val inQueries = IncomingQueries(database)
    val metadataQueries = PaymentsMetadataQueries(database)

    override suspend fun addOutgoingPayment(
        outgoingPayment: OutgoingPayment
    ) {
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

    override suspend fun addLightningOutgoingPaymentParts(
        parentId: UUID,
        parts: List<LightningOutgoingPayment.Part>
    ) {
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

    override suspend fun completeLightningOutgoingPayment(
        id: UUID,
        status: LightningOutgoingPayment.Status.Completed
    ) {
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

    override suspend fun completeLightningOutgoingPaymentPart(
        parentId: UUID,
        partId: UUID,
        status: LightningOutgoingPayment.Part.Status.Completed
    ) {
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

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        database.outgoingPaymentsQueries.get(id).executeAsOneOrNull() as? LightningOutgoingPayment
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            val paymentId = database.outgoingPaymentsQueries.getParentId(partId).executeAsOneOrNull()!!
            database.outgoingPaymentsQueries.get(paymentId).executeAsOneOrNull() as? LightningOutgoingPayment
        }
    }

    override suspend fun listLightningOutgoingPayments(
        paymentHash: ByteVector32
    ): List<LightningOutgoingPayment> = withContext(Dispatchers.Default) {
        database.outgoingPaymentsQueries.listByPaymentHash(paymentHash).executeAsList().filterIsInstance<LightningOutgoingPayment>()
    }

    override suspend fun getInboundLiquidityPurchase(fundingTxId: TxId): InboundLiquidityOutgoingPayment? {
        return withContext(Dispatchers.Default) {
            database.outgoingPaymentsQueries.listByTxId(fundingTxId).executeAsList()
                .filterIsInstance<InboundLiquidityOutgoingPayment>()
                .firstOrNull()
        }
    }

    // ---- incoming payments

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) {
        return withContext(Dispatchers.Default) {
            database.transaction {
                inQueries.addIncomingPayment(incomingPayment)
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

    override suspend fun receiveLightningPayment(paymentHash: ByteVector32, parts: List<LightningIncomingPayment.Part>) {
        withContext(Dispatchers.Default) {
            inQueries.receivePayment(paymentHash, parts)
        }
    }

    override suspend fun setLocked(txId: TxId) {
        database.transaction {
            val lockedAt = currentTimestampMillis()
            database.onChainTransactionsQueries.setLocked(tx_id = txId, locked_at = lockedAt)
            database.onChainTransactionsQueries
                .listByTxid(txId)
                .executeAsList()
                .map { WalletPaymentAdapter.decode(it) }
                .forEach { payment ->
                    @Suppress("DEPRECATION")
                    when (payment) {
                        is LightningIncomingPayment -> {}
                        is OnChainIncomingPayment -> {
                            val payment1 = payment.setLocked(lockedAt)
                            database.incomingPaymentsQueries.updateReceived(id = payment1.id, data = payment1, receivedAt = lockedAt)
                        }
                        is LegacyPayToOpenIncomingPayment -> {}
                        is LegacySwapInIncomingPayment -> {}
                        is LightningOutgoingPayment -> {}
                        is OnChainOutgoingPayment -> {
                            // NB: the completed status uses either the locked or confirmed timestamp, depending on the on-chain payment type
                            when (val payment1 = payment.setLocked(lockedAt)) {
                                is InboundLiquidityOutgoingPayment ->
                                    database.outgoingPaymentsQueries.update(id = payment1.id, data = payment1, completed_at = lockedAt)
                                else ->
                                    database.outgoingPaymentsQueries.update(id = payment1.id, data = payment1, completed_at = null)
                            }
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
                .listByTxid(txId)
                .executeAsList()
                .map { WalletPaymentAdapter.decode(it) }
                .forEach { payment ->
                    @Suppress("DEPRECATION")
                    when (payment) {
                        is LightningIncomingPayment -> {}
                        is OnChainIncomingPayment -> {
                            val payment1 = payment.setConfirmed(confirmedAt)
                            database.incomingPaymentsQueries.updateReceived(id = payment1.id, data = payment1, receivedAt = null)
                        }
                        is LegacyPayToOpenIncomingPayment -> {}
                        is LegacySwapInIncomingPayment -> {}
                        is LightningOutgoingPayment -> {}
                        is OnChainOutgoingPayment -> {
                            // NB: the completed status uses either the locked or confirmed timestamp, depending on the on-chain payment type
                            when (val payment1 = payment.setConfirmed(confirmedAt)) {
                                is InboundLiquidityOutgoingPayment ->
                                    database.outgoingPaymentsQueries.update(id = payment1.id, data = payment1, completed_at = null)
                                else ->
                                    database.outgoingPaymentsQueries.update(id = payment1.id, data = payment1, completed_at = confirmedAt)
                            }
                        }
                    }
                }
        }
    }

    override suspend fun getLightningIncomingPayment(paymentHash: ByteVector32): LightningIncomingPayment? = withContext(Dispatchers.Default) {
        inQueries.getIncomingPayment(paymentHash) as? LightningIncomingPayment
    }

    /** Useful for backwards compatibility because [[getLightningIncomingPayment]] does not return [[LegacyPayToOpenIncomingPayment]] */
    suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? = withContext(Dispatchers.Default) {
        inQueries.getIncomingPayment(paymentHash)
    }

    override suspend fun listLightningExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> = withContext(Dispatchers.Default) {
        inQueries.listExpiredPayments(fromCreatedAt, toCreatedAt)
    }

    override suspend fun removeLightningIncomingPayment(paymentHash: ByteVector32): Boolean = withContext(Dispatchers.Default) {
        inQueries.deleteIncomingPayment(paymentHash)
    }

    // ---- list payments with filter

    suspend fun listIncomingPayments(from: Long, to: Long, limit: Long, offset: Long, listAll: Boolean): List<Pair<IncomingPayment, String?>> {
        return withContext(Dispatchers.Default) {
            if (listAll) {
                inQueries.listPayments(from, to, limit, offset)
            } else {
                inQueries.listReceivedPayments(from, to, limit, offset)
            }
        }
    }

    suspend fun listIncomingPaymentsForExternalId(externalId: String, from: Long, to: Long, limit: Long, offset: Long, listAll: Boolean): List<Pair<IncomingPayment, String?>> {
        return withContext(Dispatchers.Default) {
            if (listAll) {
                inQueries.listPaymentsForExternalId(externalId, from, to, limit, offset)
            } else {
                inQueries.listReceivedPaymentsForExternalId(externalId, from, to, limit, offset)
            }
        }
    }

    suspend fun listOutgoingPayments(from: Long = 0, to: Long = Long.MAX_VALUE, limit: Long = Long.MAX_VALUE, offset: Long = 0, listAll: Boolean = false): List<OutgoingPayment> {
        return withContext(Dispatchers.Default) {
            if (listAll) {
                database.outgoingPaymentsQueries.list(created_at_from = from, created_at_to = to, limit = limit, offset = offset).executeAsList()
            } else {
                database.outgoingPaymentsQueries.listSuccessful(sent_at_from = from, sent_at_to = to, limit = limit, offset = offset).executeAsList()
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
                database.paymentsQueries.list(
                    completed_at_from = from,
                    completed_at_to = to,
                    limit = batchSize,
                    offset = batchOffset
                )
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
