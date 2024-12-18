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
    ByteVectorSerializer::class,
    ByteVector32Serializer::class,
    UUIDSerializer::class,
    OutpointSerializer::class,
)
@file:Suppress("DEPRECATION")

package fr.acinq.lightning.bin.db.migrations.v3.types

import fr.acinq.bitcoin.*
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.migrations.v3.json.*
import fr.acinq.lightning.bin.db.migrations.v4.types.liquidityads.FundingFeeData
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.UUID.Companion.randomUUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.LiquidityAds
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.json.Json


private enum class IncomingReceivedWithTypeVersion {
    MULTIPARTS_V1,
}

private sealed class IncomingReceivedWithData {

    @Serializable
    sealed class Part : IncomingReceivedWithData() {
        sealed class Htlc : Part() {
            @Deprecated("Replaced by [Htlc.V1], which supports the liquidity ads funding fee")
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.Htlc.V0")
            data class V0(
                val amount: MilliSatoshi,
                val channelId: ByteVector32,
                val htlcId: Long
            ) : Htlc()

            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.Htlc.V1")
            data class V1(
                val amountReceived: MilliSatoshi,
                val channelId: ByteVector32,
                val htlcId: Long,
                val fundingFee: FundingFeeData?,
            ) : Htlc()
        }

        sealed class NewChannel : Part() {
            @Deprecated("Legacy type. Use V1 instead for new parts, with the new `id` field.")
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.NewChannel.V0")
            data class V0(
                @Serializable val amount: MilliSatoshi,
                @Serializable val fees: MilliSatoshi,
                @Serializable val channelId: ByteVector32?
            ) : NewChannel()

            /** V1 contains a new `id` field that ensure that each [NewChannel] is unique. Old V0 data will use a random UUID to respect the [IncomingPayment.ReceivedWith.NewChannel] interface. */
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.NewChannel.V1")
            data class V1(
                @Serializable val id: UUID,
                @Serializable val amount: MilliSatoshi,
                @Serializable val fees: MilliSatoshi,
                @Serializable val channelId: ByteVector32?
            ) : NewChannel()

            /** V2 supports dual funding. New fields: service/miningFees, channel id, funding tx id, and the confirmation/lock timestamps. Id is removed. */
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.NewChannel.V2")
            data class V2(
                val amount: MilliSatoshi,
                val serviceFee: MilliSatoshi,
                val miningFee: Satoshi,
                val channelId: ByteVector32,
                val txId: ByteVector32,
                val confirmedAt: Long?,
                val lockedAt: Long?,
            ) : NewChannel()
        }

        sealed class SpliceIn : Part() {
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.SpliceIn.V0")
            data class V0(
                val amount: MilliSatoshi,
                val serviceFee: MilliSatoshi,
                val miningFee: Satoshi,
                val channelId: ByteVector32,
                val txId: ByteVector32,
                val confirmedAt: Long?,
                val lockedAt: Long?,
            ) : SpliceIn()
        }

        sealed class FeeCredit : Part() {
            @Serializable
            @SerialName("fr.acinq.phoenix.db.payments.IncomingReceivedWithData.Part.FeeCredit.V0")
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
        ): List<Part> =
            when (typeVersion) {
                IncomingReceivedWithTypeVersion.MULTIPARTS_V1 -> Json.decodeFromString(SetSerializer(Part.serializer()), String(bytes = blob, charset = Charsets.UTF_8)).toList()
            }
    }
}

private enum class IncomingOriginTypeVersion {
    INVOICE_V0,
    SWAPIN_V0,
    ONCHAIN_V0,
    OFFER_V0,
}

private sealed class IncomingOriginData {

    sealed class Invoice : IncomingOriginData() {
        @Serializable
        data class V0(val paymentRequest: String) : Invoice()
    }

    sealed class SwapIn : IncomingOriginData() {
        @Serializable
        data class V0(val address: String?) : SwapIn()
    }

    sealed class OnChain : IncomingOriginData() {
        @Serializable
        data class V0(val txId: ByteVector32, val outpoints: List<OutPoint>) : OnChain()
    }

    sealed class Offer : IncomingOriginData() {
        @Serializable
        data class V0(val encodedMetadata: ByteVector) : Offer()
    }

    companion object {
        fun deserialize(typeVersion: IncomingOriginTypeVersion, blob: ByteArray): IncomingOriginData =
            when (typeVersion) {
                IncomingOriginTypeVersion.INVOICE_V0 -> Json.decodeFromString<Invoice.V0>(blob.decodeToString())
                IncomingOriginTypeVersion.SWAPIN_V0 -> Json.decodeFromString<SwapIn.V0>(blob.decodeToString())
                IncomingOriginTypeVersion.ONCHAIN_V0 -> Json.decodeFromString<OnChain.V0>(blob.decodeToString())
                IncomingOriginTypeVersion.OFFER_V0 -> Json.decodeFromString<Offer.V0>(blob.decodeToString())
            }
    }
}

private fun mapLightningIncomingPaymentPart(part: IncomingReceivedWithData.Part, receivedAt: Long): LightningIncomingPayment.Part = when (part) {
    is IncomingReceivedWithData.Part.Htlc.V0 -> LightningIncomingPayment.Part.Htlc(
        amountReceived = part.amount,
        channelId = part.channelId,
        htlcId = part.htlcId,
        fundingFee = null,
        receivedAt = receivedAt
    )
    is IncomingReceivedWithData.Part.Htlc.V1 -> LightningIncomingPayment.Part.Htlc(
        amountReceived = part.amountReceived,
        channelId = part.channelId,
        htlcId = part.htlcId,
        fundingFee = when (part.fundingFee) {
            is FundingFeeData.V0 -> LiquidityAds.FundingFee(part.fundingFee.amount, part.fundingFee.fundingTxId)
            null -> null
        },
        receivedAt = receivedAt
    )
    is IncomingReceivedWithData.Part.FeeCredit.V0 -> LightningIncomingPayment.Part.FeeCredit(
        amountReceived = part.amount,
        receivedAt = receivedAt
    )
    else -> error("unexpected part=$part")
}

@Suppress("DEPRECATION")
fun mapIncomingPaymentFromV3(
    @Suppress("UNUSED_PARAMETER") payment_hash: ByteArray,
    preimage: ByteArray,
    created_at: Long,
    origin_type: String,
    origin_blob: ByteArray,
    @Suppress("UNUSED_PARAMETER") received_amount_msat: Long?,
    received_at: Long?,
    received_with_type: String?,
    received_with_blob: ByteArray?,
): IncomingPayment {
    val origin = IncomingOriginData.deserialize(IncomingOriginTypeVersion.valueOf(origin_type), origin_blob)
    val parts = when {
        received_with_type != null && received_with_blob != null -> IncomingReceivedWithData.deserialize(IncomingReceivedWithTypeVersion.valueOf(received_with_type), received_with_blob)
        else -> emptyList()
    }
    return when {
        received_at == null && origin is IncomingOriginData.Invoice.V0 ->
            Bolt11IncomingPayment(
                preimage = ByteVector32(preimage),
                paymentRequest = Bolt11Invoice.read(origin.paymentRequest).get(),
                parts = emptyList(),
                createdAt = created_at
            )
        received_at == null && origin is IncomingOriginData.Offer.V0 ->
            Bolt12IncomingPayment(
                preimage = ByteVector32(preimage),
                metadata = OfferPaymentMetadata.decode(origin.encodedMetadata),
                parts = emptyList(),
                createdAt = created_at
            )
        received_at != null && origin is IncomingOriginData.Invoice.V0 && parts.all { it is IncomingReceivedWithData.Part.Htlc || it is IncomingReceivedWithData.Part.FeeCredit } ->
            Bolt11IncomingPayment(
                preimage = ByteVector32(preimage),
                paymentRequest = Bolt11Invoice.read(origin.paymentRequest).get(),
                parts = parts.map { mapLightningIncomingPaymentPart(it, received_at) }
            )
        received_at != null && origin is IncomingOriginData.Offer.V0 && parts.all { it is IncomingReceivedWithData.Part.Htlc || it is IncomingReceivedWithData.Part.FeeCredit } ->
            Bolt12IncomingPayment(
                preimage = ByteVector32(preimage),
                metadata = OfferPaymentMetadata.decode(origin.encodedMetadata),
                parts = parts.map { mapLightningIncomingPaymentPart(it, received_at) }
            )
        received_at != null && (origin is IncomingOriginData.Invoice || origin is IncomingOriginData.Offer) && parts.any { it is IncomingReceivedWithData.Part.SpliceIn || it is IncomingReceivedWithData.Part.NewChannel } ->
            LegacyPayToOpenIncomingPayment(
                paymentPreimage = ByteVector32(preimage),
                origin = when (origin) {
                    is IncomingOriginData.Invoice.V0 -> LegacyPayToOpenIncomingPayment.Origin.Invoice(Bolt11Invoice.read(origin.paymentRequest).get())
                    is IncomingOriginData.Offer.V0 -> LegacyPayToOpenIncomingPayment.Origin.Offer(OfferPaymentMetadata.decode(origin.encodedMetadata))
                    else -> error("impossible")
                },
                parts = parts.map {
                    when (it) {
                        is IncomingReceivedWithData.Part.Htlc.V0 -> LegacyPayToOpenIncomingPayment.Part.Lightning(
                            amountReceived = it.amount,
                            channelId = it.channelId,
                            htlcId = it.htlcId
                        )
                        is IncomingReceivedWithData.Part.Htlc.V1 -> LegacyPayToOpenIncomingPayment.Part.Lightning(
                            amountReceived = it.amountReceived,
                            channelId = it.channelId,
                            htlcId = it.htlcId
                        )
                        is IncomingReceivedWithData.Part.NewChannel.V0 -> LegacyPayToOpenIncomingPayment.Part.OnChain(
                            amountReceived = it.amount,
                            serviceFee = it.fees,
                            miningFee = 0.sat,
                            channelId = it.channelId ?: ByteVector32.Zeroes,
                            txId = TxId(ByteVector32.Zeroes),
                            confirmedAt = received_at,
                            lockedAt = received_at,
                        )
                        is IncomingReceivedWithData.Part.NewChannel.V1 -> LegacyPayToOpenIncomingPayment.Part.OnChain(
                            amountReceived = it.amount,
                            serviceFee = it.fees,
                            miningFee = 0.sat,
                            channelId = it.channelId ?: ByteVector32.Zeroes,
                            txId = TxId(ByteVector32.Zeroes),
                            confirmedAt = received_at,
                            lockedAt = received_at,
                        )
                        is IncomingReceivedWithData.Part.NewChannel.V2 -> LegacyPayToOpenIncomingPayment.Part.OnChain(
                            amountReceived = it.amount,
                            serviceFee = it.serviceFee,
                            miningFee = it.miningFee,
                            channelId = it.channelId,
                            txId = TxId(it.txId),
                            confirmedAt = it.confirmedAt,
                            lockedAt = it.lockedAt,
                        )
                        is IncomingReceivedWithData.Part.SpliceIn.V0 -> LegacyPayToOpenIncomingPayment.Part.OnChain(
                            amountReceived = it.amount,
                            serviceFee = it.serviceFee,
                            miningFee = it.miningFee,
                            channelId = it.channelId,
                            txId = TxId(it.txId),
                            confirmedAt = it.confirmedAt,
                            lockedAt = it.lockedAt,
                        )
                        else -> error("unexpected part=$it")
                    }
                },
                createdAt = created_at,
                completedAt = received_at
            )
        received_at != null && origin is IncomingOriginData.OnChain.V0 && parts.all { it is IncomingReceivedWithData.Part.NewChannel } -> NewChannelIncomingPayment(
            id = randomUUID(),
            amountReceived = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> it.amount
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> it.amount
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.amount
                }
            }.sum(),
            serviceFee = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> it.fees
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> it.fees
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.serviceFee
                }
            }.sum(),
            miningFee = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> 0.sat
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> 0.sat
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.miningFee
                }
            }.sum(),
            channelId = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> it.channelId ?: ByteVector32.Zeroes
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> it.channelId ?: ByteVector32.Zeroes
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.channelId
                }
            }.first(),
            txId = TxId(origin.txId),
            localInputs = origin.outpoints.toSet(),
            createdAt = created_at,
            confirmedAt = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> received_at
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> received_at
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.confirmedAt
                }
            }.first(),
            lockedAt = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> received_at
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> received_at
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.lockedAt
                }
            }.first(),
        )
        received_at != null && origin is IncomingOriginData.OnChain.V0 && parts.all { it is IncomingReceivedWithData.Part.SpliceIn } -> SpliceInIncomingPayment(
            id = randomUUID(),
            amountReceived = parts.filterIsInstance<IncomingReceivedWithData.Part.SpliceIn>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.SpliceIn.V0 -> it.amount
                }
            }.sum(),
            miningFee = parts.filterIsInstance<IncomingReceivedWithData.Part.SpliceIn>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.SpliceIn.V0 -> it.miningFee
                }
            }.sum(),
            channelId = parts.filterIsInstance<IncomingReceivedWithData.Part.SpliceIn>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.SpliceIn.V0 -> it.channelId
                }
            }.first(),
            txId = TxId(origin.txId),
            localInputs = origin.outpoints.toSet(),
            createdAt = created_at,
            confirmedAt = parts.filterIsInstance<IncomingReceivedWithData.Part.SpliceIn>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.SpliceIn.V0 -> it.confirmedAt
                }
            }.first(),
            lockedAt = parts.filterIsInstance<IncomingReceivedWithData.Part.SpliceIn>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.SpliceIn.V0 -> it.lockedAt
                }
            }.first(),
        )
        received_at != null && origin is IncomingOriginData.SwapIn.V0 && parts.all { it is IncomingReceivedWithData.Part.NewChannel } -> LegacySwapInIncomingPayment(
            id = randomUUID(),
            amountReceived = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> it.amount
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> it.amount
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.amount
                }
            }.sum(),
            fees = parts.filterIsInstance<IncomingReceivedWithData.Part.NewChannel>().map {
                when (it) {
                    is IncomingReceivedWithData.Part.NewChannel.V0 -> it.fees
                    is IncomingReceivedWithData.Part.NewChannel.V1 -> it.fees
                    is IncomingReceivedWithData.Part.NewChannel.V2 -> it.serviceFee
                }
            }.sum(),
            address = origin.address,
            createdAt = created_at,
            completedAt = received_at
        )
        else -> TODO("unsupported payment origin=${origin::class} parts=$parts")
    }
}