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

@file:UseSerializers(
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class,
    ByteVector32Serializer::class,
    UUIDSerializer::class,
)

package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.payments.liquidityads.FundingFeeData
import fr.acinq.lightning.bin.db.payments.liquidityads.FundingFeeData.Companion.asCanonical
import fr.acinq.lightning.bin.db.payments.liquidityads.FundingFeeData.Companion.asDb
import fr.acinq.lightning.bin.db.serializers.v1.*
import fr.acinq.lightning.db.IncomingPayment
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.SetSerializer


enum class IncomingReceivedWithTypeVersion {
    MULTIPARTS_V1,
}

sealed class IncomingReceivedWithData {

    @Serializable
    sealed class Part : IncomingReceivedWithData() {
        sealed class Htlc : Part() {
            @Deprecated("Replaced by [Htlc.V1], which supports the liquidity ads funding fee")
            @Serializable
            data class V0(
                @Serializable val amount: MilliSatoshi,
                @Serializable val channelId: ByteVector32,
                val htlcId: Long
            ) : Htlc()

            @Serializable
            data class V1(
                val amountReceived: MilliSatoshi,
                val channelId: ByteVector32,
                val htlcId: Long,
                val fundingFee: FundingFeeData?,
            ) : Htlc()
        }

        sealed class NewChannel : Part() {
            /** V2 supports dual funding. New fields: service/miningFees, channel id, funding tx id, and the confirmation/lock timestamps. Id is removed. */
            @Serializable
            data class V2(
                @Serializable val amount: MilliSatoshi,
                @Serializable val serviceFee: MilliSatoshi,
                @Serializable val miningFee: Satoshi,
                @Serializable val channelId: ByteVector32,
                @Serializable val txId: ByteVector32,
                @Serializable val confirmedAt: Long?,
                @Serializable val lockedAt: Long?,
            ) : NewChannel()
        }

        sealed class SpliceIn : Part() {
            @Serializable
            data class V0(
                @Serializable val amount: MilliSatoshi,
                @Serializable val serviceFee: MilliSatoshi,
                @Serializable val miningFee: Satoshi,
                @Serializable val channelId: ByteVector32,
                @Serializable val txId: ByteVector32,
                @Serializable val confirmedAt: Long?,
                @Serializable val lockedAt: Long?,
            ) : SpliceIn()
        }

        sealed class FeeCredit : Part() {
            @Serializable
            data class V0(
                val amount: MilliSatoshi
            ) : FeeCredit()
        }
    }

    companion object {
        /** Deserializes a received-with blob from the database using the given [typeVersion]. */
        fun deserialize(
            typeVersion: IncomingReceivedWithTypeVersion,
            blob: ByteArray,
        ): List<IncomingPayment.ReceivedWith> = DbTypesHelper.decodeBlob(blob) { json, _ ->
            when (typeVersion) {
                IncomingReceivedWithTypeVersion.MULTIPARTS_V1 -> DbTypesHelper.polymorphicFormat.decodeFromString(SetSerializer(PolymorphicSerializer(Part::class)), json).map {
                    @Suppress("DEPRECATION")
                    when (it) {
                        is Part.Htlc.V0 -> IncomingPayment.ReceivedWith.LightningPayment(
                            amountReceived = it.amount,
                            channelId = it.channelId,
                            htlcId = it.htlcId,
                            fundingFee = null
                        )
                        is Part.Htlc.V1 -> IncomingPayment.ReceivedWith.LightningPayment(
                            amountReceived = it.amountReceived,
                            channelId = it.channelId,
                            htlcId = it.htlcId,
                            fundingFee = it.fundingFee?.asCanonical()
                        )
                        is Part.NewChannel.V2 -> IncomingPayment.ReceivedWith.NewChannel(
                            amountReceived = it.amount,
                            serviceFee = it.serviceFee,
                            miningFee = it.miningFee,
                            channelId = it.channelId,
                            txId = TxId(it.txId),
                            confirmedAt = it.confirmedAt,
                            lockedAt = it.lockedAt,
                        )
                        is Part.SpliceIn.V0 -> IncomingPayment.ReceivedWith.SpliceIn(
                            amountReceived = it.amount,
                            serviceFee = it.serviceFee,
                            miningFee = it.miningFee,
                            channelId = it.channelId,
                            txId = TxId(it.txId),
                            confirmedAt = it.confirmedAt,
                            lockedAt = it.lockedAt,
                        )
                        is Part.FeeCredit.V0 -> IncomingPayment.ReceivedWith.AddedToFeeCredit(
                            amountReceived = it.amount
                        )
                    }
                }
            }
        }
    }
}

/** Only serialize received_with into the [IncomingReceivedWithTypeVersion.MULTIPARTS_V1] type. */
fun List<IncomingPayment.ReceivedWith>.mapToDb(): Pair<IncomingReceivedWithTypeVersion, ByteArray>? = map {
    when (it) {
        is IncomingPayment.ReceivedWith.LightningPayment -> IncomingReceivedWithData.Part.Htlc.V1(
            amountReceived = it.amountReceived,
            channelId = it.channelId,
            htlcId = it.htlcId,
            fundingFee = it.fundingFee?.asDb()
        )
        is IncomingPayment.ReceivedWith.NewChannel -> IncomingReceivedWithData.Part.NewChannel.V2(
            amount = it.amountReceived,
            serviceFee = it.serviceFee,
            miningFee = it.miningFee,
            channelId = it.channelId,
            txId = it.txId.value,
            confirmedAt = it.confirmedAt,
            lockedAt = it.lockedAt,
        )
        is IncomingPayment.ReceivedWith.SpliceIn -> IncomingReceivedWithData.Part.SpliceIn.V0(
            amount = it.amountReceived,
            serviceFee = it.serviceFee,
            miningFee = it.miningFee,
            channelId = it.channelId,
            txId = it.txId.value,
            confirmedAt = it.confirmedAt,
            lockedAt = it.lockedAt,
        )
        is IncomingPayment.ReceivedWith.AddedToFeeCredit -> IncomingReceivedWithData.Part.FeeCredit.V0(
            amount = it.amountReceived
        )
    }
}.takeIf { it.isNotEmpty() }?.toSet()?.let {
    IncomingReceivedWithTypeVersion.MULTIPARTS_V1 to DbTypesHelper.polymorphicFormat.encodeToString(
        SetSerializer(PolymorphicSerializer(IncomingReceivedWithData.Part::class)), it
    ).toByteArray(Charsets.UTF_8)
}
