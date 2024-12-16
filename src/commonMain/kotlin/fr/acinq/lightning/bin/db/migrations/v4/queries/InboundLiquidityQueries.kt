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

package fr.acinq.lightning.bin.db.migrations.v4.queries

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.bin.db.migrations.v4.types.liquidityads.PurchaseData
import fr.acinq.lightning.db.InboundLiquidityOutgoingPayment
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector32

class InboundLiquidityQueries {

    companion object {
        fun mapPayment(
            id: String,
            mining_fees_sat: Long,
            channel_id: ByteArray,
            tx_id: ByteArray,
            lease_type: String,
            lease_blob: ByteArray,
            @Suppress("UNUSED_PARAMETER") payment_details_type: String?,
            created_at: Long,
            confirmed_at: Long?,
            locked_at: Long?
        ): InboundLiquidityOutgoingPayment {
            val purchase = PurchaseData.decodeAsCanonical(lease_type, lease_blob)
            return InboundLiquidityOutgoingPayment(
                id = UUID.fromString(id),
                // Attention! With the new OTF and lightning-kmp#710, the liquidity mining fee is split between `localMiningFee` and `purchase.miningFee`.
                // However, for compatibility reasons with legacy lease data, we keep storing the "merged" mining fee in the mining_fee_sat column.
                // It means that to retrieve the `localMiningFee`, we must subtract `purchase.miningFee` from the `mining_fee_sat` column.
                localMiningFees = mining_fees_sat.sat - purchase.fees.miningFee,
                channelId = channel_id.toByteVector32(),
                txId = TxId(tx_id),
                purchase = purchase,
                createdAt = created_at,
                confirmedAt = confirmed_at,
                lockedAt = locked_at
            )
        }
    }
}