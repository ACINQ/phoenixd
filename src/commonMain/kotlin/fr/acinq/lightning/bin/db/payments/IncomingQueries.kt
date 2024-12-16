/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.Bolt11IncomingPayment
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.db.LightningIncomingPayment.Companion.addReceivedParts
import fr.acinq.lightning.db.OnChainIncomingPayment
import fr.acinq.lightning.utils.UUID
import fr.acinq.phoenix.db.PhoenixDatabase

class IncomingQueries(private val database: PhoenixDatabase) {

    private val queries = database.incomingPaymentsQueries

    fun addIncomingPayment(
        incomingPayment: IncomingPayment
    ) {
        queries.insert(
            id = incomingPayment.id,
            payment_hash = (incomingPayment as? LightningIncomingPayment)?.paymentHash,
            tx_id = (incomingPayment as? OnChainIncomingPayment)?.txId,
            created_at = incomingPayment.createdAt,
            received_at = incomingPayment.completedAt,
            data_ = incomingPayment
        )
    }

    fun receivePayment(
        paymentHash: ByteVector32,
        parts: List<LightningIncomingPayment.Part>
    ) {
        database.transaction {
            when (val paymentInDb = getIncomingPayment(paymentHash)) {
                null -> error("missing payment for payment_hash=$paymentHash")
                is LightningIncomingPayment -> {
                    val paymentInDb1 = paymentInDb.addReceivedParts(parts)
                    queries.updateReceived(
                        id = paymentInDb1.id,
                        data = paymentInDb1,
                        receivedAt = paymentInDb1.completedAt
                    )
                }
                else -> error("unexpected type: $paymentInDb")
            }
        }
    }

    fun getIncomingPayment(id: UUID): IncomingPayment? {
        return queries.get(id = id).executeAsOneOrNull()?.data_
    }

    fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? {
        return queries.getByPaymentHash(payment_hash = paymentHash).executeAsOneOrNull()?.data_
    }

    fun getOldestReceivedDate(): Long? {
        return queries.getOldestReceivedDate().executeAsOneOrNull()
    }

    fun listPayments(from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listCreatedWithin(from = from, to = to, limit, offset).executeAsList().map { it.data_ to it.external_id }
    }

    fun listPaymentsForExternalId(externalId: String, from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listCreatedForExternalIdWithin(externalId, from, to, limit, offset).executeAsList().map { it.data_ to it.external_id }
    }

    fun listReceivedPayments(from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listReceivedWithin(from = from, to = to, limit, offset).executeAsList().map { it.data_ to it.external_id }
    }

    fun listReceivedPaymentsForExternalId(externalId: String, from: Long, to: Long, limit: Long, offset: Long): List<Pair<IncomingPayment, String?>> {
        return queries.listReceivedForExternalIdWithin(externalId, from, to, limit, offset).executeAsList().map { it.data_ to it.external_id }
    }

    fun listExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> {
        return queries.listCreatedWithinNoPaging(fromCreatedAt, toCreatedAt).executeAsList()
            .filterIsInstance<Bolt11IncomingPayment>()
            .filter { it.parts.isEmpty() && it.paymentRequest.isExpired() }
    }

    /** Try to delete an incoming payment ; return true if an element was deleted, false otherwise. */
    fun deleteIncomingPayment(paymentHash: ByteVector32): Boolean {
        return database.transactionWithResult {
            queries.delete(payment_hash = paymentHash)
            queries.changes().executeAsOne() != 0L
        }
    }
}
