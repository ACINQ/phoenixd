/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.lightning.bin.db.serializers.v1

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.wire.LiquidityAds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object TxIdSerializer : AbstractStringSerializer<TxId>(
    name = "TxId",
    toString = TxId::toString,
    fromString = ::TxId
)

object FundingFeeSerializer : KSerializer<LiquidityAds.FundingFee> {

    @Serializable
    private data class FundingFeeSurrogate(
        @Serializable(with = MilliSatoshiSerializer::class) val amount: MilliSatoshi,
        @Serializable(with = TxIdSerializer::class) val fundingTxId: TxId
    )

    override val descriptor: SerialDescriptor = FundingFeeSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LiquidityAds.FundingFee) {
        val surrogate = FundingFeeSurrogate(amount = value.amount, fundingTxId = value.fundingTxId)
        return encoder.encodeSerializableValue(FundingFeeSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): LiquidityAds.FundingFee {
        val surrogate = decoder.decodeSerializableValue(FundingFeeSurrogate.serializer())
        return LiquidityAds.FundingFee(amount = surrogate.amount, fundingTxId = surrogate.fundingTxId)
    }
}
