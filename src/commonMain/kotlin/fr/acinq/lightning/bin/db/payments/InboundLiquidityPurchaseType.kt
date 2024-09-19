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

@file:UseSerializers(
    ByteVectorSerializer::class,
    ByteVector32Serializer::class,
    ByteVector64Serializer::class,
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class
)

package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.serializers.v1.*
import fr.acinq.lightning.db.InboundLiquidityOutgoingPayment
import fr.acinq.lightning.wire.LiquidityAds
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

enum class InboundLiquidityPurchaseType {
    @Deprecated("obsolete with the new on-the-fly channel funding that replaces lease -> purchase")
    LEASE_V0,
    PURCHASE_STANDARD,
    PURCHASE_FEE_CREDIT,
}

enum class InboundLiquidityPaymentDetailsType {
    CHANNEL_BALANCE,
    FUTURE_HTLC,
    FUTURE_HTLC_WITH_PREIMAGE,
    CHANNEL_BALANCE_FUTURE_HTLC,
}

@Suppress("DEPRECATION")
@Deprecated("obsolete with the new on-the-fly channel funding that replaces lease -> purchase")
sealed class InboundLiquidityLeaseData {

    @Serializable
    data class V0(
        // these legacy data can still be mapped to the new model
        val amount: Satoshi,
        val miningFees: Satoshi,
        val serviceFee: Satoshi,
        // the other legacy data are unused and ignored
        val sellerSig: ByteVector64,
        val witnessFundingScript: ByteVector,
        val witnessLeaseDuration: Int,
        val witnessLeaseEnd: Int,
        val witnessMaxRelayFeeProportional: Int,
        val witnessMaxRelayFeeBase: MilliSatoshi
    ) : InboundLiquidityLeaseData()
}

@Serializable
sealed class InboundLiquidityPaymentDetailsData {
    sealed class ChannelBalance : InboundLiquidityPaymentDetailsData() {
        @Serializable
        data object V0 : ChannelBalance()
    }

    sealed class FutureHtlc : InboundLiquidityPaymentDetailsData() {
        @Serializable
        data class V0(val paymentHashes: List<ByteVector32>) : FutureHtlc()
    }

    sealed class FutureHtlcWithPreimage : InboundLiquidityPaymentDetailsData() {
        @Serializable
        data class V0(val preimages: List<ByteVector32>) : FutureHtlcWithPreimage()
    }

    sealed class ChannelBalanceForFutureHtlc : InboundLiquidityPaymentDetailsData() {
        @Serializable
        data class V0(val paymentHashes: List<ByteVector32>) : ChannelBalanceForFutureHtlc()
    }

    companion object {
        /** Deserializes a json-encoded blob containing a [LiquidityAds.PaymentDetails] object. */
        fun deserialize(blob: ByteArray): LiquidityAds.PaymentDetails = DbTypesHelper.decodeBlob(blob) { json, _ ->
            when (val data = DbTypesHelper.polymorphicFormat.decodeFromString(PolymorphicSerializer(InboundLiquidityPaymentDetailsData::class), json)) {
                is ChannelBalance.V0 -> LiquidityAds.PaymentDetails.FromChannelBalance
                is FutureHtlc.V0 -> LiquidityAds.PaymentDetails.FromFutureHtlc(data.paymentHashes)
                is FutureHtlcWithPreimage.V0 -> LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage(data.preimages)
                is ChannelBalanceForFutureHtlc.V0 -> LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(data.paymentHashes)
            }
        }
    }
}

@Serializable
sealed class InboundLiquidityPurchaseData {

    sealed class Standard : InboundLiquidityPurchaseData() {
        @Serializable
        data class V0(
            val amount: Satoshi,
            val miningFees: Satoshi,
            val serviceFee: Satoshi,
        ) : Standard()
    }

    sealed class WithFeeCredit : InboundLiquidityPurchaseData() {
        @Serializable
        data class V0(
            val amount: Satoshi,
            val miningFees: Satoshi,
            val serviceFee: Satoshi,
            val feeCreditUsed: MilliSatoshi,
        ) : WithFeeCredit()
    }

    companion object {
        /**
         * Deserializes purchase and payment_details json-encoded blobs into a [LiquidityAds.Purchase] object.
         *
         * @param typeVersion only used for the legacy leased data, where the blob did not contain the type of the object. The "modern" blobs are expected
         *                    to have been created by a polymorphic serializer, hence they already contain the type and do not need the type_version.
         * */
        @Suppress("DEPRECATION")
        fun deserialize(
            typeVersion: InboundLiquidityPurchaseType,
            blob: ByteArray,
            paymentDetailsBlob: ByteArray,
        ): LiquidityAds.Purchase = DbTypesHelper.decodeBlob(blob) { json, format ->
            when (typeVersion) {
                // map legacy lease data into the modern [LiquidityAds.Purchase] object ; uses fake payment details data
                InboundLiquidityPurchaseType.LEASE_V0 -> format.decodeFromString<InboundLiquidityLeaseData.V0>(json).let {
                    LiquidityAds.Purchase.Standard(
                        amount = it.amount,
                        fees = LiquidityAds.Fees(miningFee = it.miningFees, serviceFee = it.serviceFee),
                        paymentDetails = LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(ByteVector32.Zeroes))
                    )
                }
                else -> {
                    when (val data = DbTypesHelper.polymorphicFormat.decodeFromString(PolymorphicSerializer(InboundLiquidityPurchaseData::class), json)) {
                        is Standard.V0 -> LiquidityAds.Purchase.Standard(
                            amount = data.amount,
                            fees = LiquidityAds.Fees(miningFee = data.miningFees, serviceFee = data.serviceFee),
                            paymentDetails = InboundLiquidityPaymentDetailsData.deserialize(paymentDetailsBlob)
                        )
                        is WithFeeCredit.V0 -> LiquidityAds.Purchase.WithFeeCredit(
                            amount = data.amount,
                            fees = LiquidityAds.Fees(miningFee = data.miningFees, serviceFee = data.serviceFee),
                            feeCreditUsed = data.feeCreditUsed,
                            paymentDetails = InboundLiquidityPaymentDetailsData.deserialize(paymentDetailsBlob)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps a [LiquidityAds.Purchase] object into 2 pairs containing the purchase type+json and the payment details type+json.
 *
 * Note that payment details are mapped to a separate object because we want to store/view these payment details information
 * in a separate column in the database (and not inside the purchase blob).
 */
fun InboundLiquidityOutgoingPayment.mapPurchaseToDb(): Pair<Pair<InboundLiquidityPurchaseType, ByteArray>,
        Pair<InboundLiquidityPaymentDetailsType, ByteArray>> {

    // map a [LiquidityAds.PaymentDetails] object into the relevant type/data pair. */
    val (detailsType, detailsData) = when (val d = purchase.paymentDetails) {
        is LiquidityAds.PaymentDetails.FromChannelBalance -> InboundLiquidityPaymentDetailsType.CHANNEL_BALANCE to InboundLiquidityPaymentDetailsData.ChannelBalance.V0
        is LiquidityAds.PaymentDetails.FromFutureHtlc -> InboundLiquidityPaymentDetailsType.FUTURE_HTLC to InboundLiquidityPaymentDetailsData.FutureHtlc.V0(d.paymentHashes)
        is LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage -> InboundLiquidityPaymentDetailsType.FUTURE_HTLC_WITH_PREIMAGE to InboundLiquidityPaymentDetailsData.FutureHtlcWithPreimage.V0(d.preimages)
        is LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc -> InboundLiquidityPaymentDetailsType.CHANNEL_BALANCE_FUTURE_HTLC to InboundLiquidityPaymentDetailsData.ChannelBalanceForFutureHtlc.V0(d.paymentHashes)
    }

    // map a [LiquidityAds.Purchase] object into the relevant type/data pair.
    val (purchaseType, purchaseData) = when (val p = this.purchase) {
        is LiquidityAds.Purchase.Standard -> InboundLiquidityPurchaseType.PURCHASE_STANDARD to InboundLiquidityPurchaseData.Standard.V0(
            amount = purchase.amount,
            miningFees = purchase.fees.miningFee,
            serviceFee = purchase.fees.serviceFee,
        )

        is LiquidityAds.Purchase.WithFeeCredit -> InboundLiquidityPurchaseType.PURCHASE_FEE_CREDIT to InboundLiquidityPurchaseData.WithFeeCredit.V0(
            amount = purchase.amount,
            miningFees = purchase.fees.miningFee,
            serviceFee = purchase.fees.serviceFee,
            feeCreditUsed = p.feeCreditUsed,
        )
    }

    // encode data with a polymorphic serializer
    return (purchaseType to purchaseData.let {
        DbTypesHelper.polymorphicFormat.encodeToString(PolymorphicSerializer(InboundLiquidityPurchaseData::class), it).toByteArray(Charsets.UTF_8)
    }) to (detailsType to detailsData.let {
        DbTypesHelper.polymorphicFormat.encodeToString(PolymorphicSerializer(InboundLiquidityPaymentDetailsData::class), it).toByteArray(Charsets.UTF_8)
    })
}
