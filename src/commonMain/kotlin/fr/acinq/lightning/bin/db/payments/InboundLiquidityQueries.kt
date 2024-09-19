/*
 * Copyright 2023 ACINQ SAS
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

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.db.InboundLiquidityOutgoingPayment
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.db.PhoenixDatabase

class InboundLiquidityQueries(val database: PhoenixDatabase) {
    private val queries = database.inboundLiquidityOutgoingPaymentsQueries

    fun add(payment: InboundLiquidityOutgoingPayment) {
        database.transaction {
            queries.insert(
                id = payment.id.toString(),
                mining_fees_sat = payment.miningFees.sat,
                channel_id = payment.channelId.toByteArray(),
                tx_id = payment.txId.value.toByteArray(),
                purchase_type = when (payment.purchase) {
                    is LiquidityAds.Purchase.Standard -> "standard"
                    is LiquidityAds.Purchase.WithFeeCredit -> "with_fee_credit"
                },
                payment_details_type = when (payment.purchase.paymentDetails) {
                    is LiquidityAds.PaymentDetails.FromChannelBalance -> "from_channel_balance"
                    is LiquidityAds.PaymentDetails.FromFutureHtlc -> "from_future_htlc"
                    is LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage -> "from_future_htlc_with_preimage"
                    is LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc -> "from_channel_balance_for_future_htlc"
                },
                purchase_json = payment.purchase,
                created_at = payment.createdAt,
                confirmed_at = payment.confirmedAt,
                locked_at = payment.lockedAt,
            )
        }
    }

    fun get(id: UUID): InboundLiquidityOutgoingPayment? {
        return queries.get(id = id.toString(), mapper = Companion::mapPayment)
            .executeAsOneOrNull()
    }

    fun getByTxId(txId: TxId): InboundLiquidityOutgoingPayment? {
        return queries.getByTxId(tx_id = txId.value.toByteArray(), mapper = Companion::mapPayment)
            .executeAsOneOrNull()
    }

    fun setConfirmed(id: UUID, confirmedAt: Long) {
        database.transaction {
            queries.setConfirmed(confirmed_at = confirmedAt, id = id.toString())
        }
    }

    fun setLocked(id: UUID, lockedAt: Long) {
        database.transaction {
            queries.setLocked(locked_at = lockedAt, id = id.toString())
        }
    }

    private companion object {
        fun mapPayment(
            id: String,
            mining_fees_sat: Long,
            channel_id: ByteArray,
            tx_id: ByteArray,
            purchase_type: String,
            payment_details_type: String,
            purchase_json: LiquidityAds.Purchase,
            created_at: Long,
            confirmed_at: Long?,
            locked_at: Long?
        ): InboundLiquidityOutgoingPayment {
            return InboundLiquidityOutgoingPayment(
                id = UUID.fromString(id),
                miningFees = mining_fees_sat.sat,
                channelId = channel_id.toByteVector32(),
                txId = TxId(tx_id),
                purchase = purchase_json,
                createdAt = created_at,
                confirmedAt = confirmed_at,
                lockedAt = locked_at
            )
        }
    }
}