@file:UseSerializers(
    ByteVectorSerializer::class,
    ByteVector32Serializer::class,
    ByteVector64Serializer::class,
    SatoshiSerializer::class,
    MilliSatoshiSerializer::class
)

package fr.acinq.phoenix.types.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.serializers.v1.*
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.types.db.LiquidityAdsDb.PaymentDetailsDb.Companion.asDb
import fr.acinq.phoenix.types.db.LiquidityAdsDb.PaymentDetailsDb.Companion.asOfficial
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

object LiquidityAdsDb {

    @Serializable
    sealed class PaymentDetailsDb {
        sealed class ChannelBalance : PaymentDetailsDb() {
            @Serializable
            data object V0 : ChannelBalance()
        }

        sealed class FutureHtlc : PaymentDetailsDb() {
            @Serializable
            data class V0(val paymentHashes: List<ByteVector32>) : FutureHtlc()
        }

        sealed class FutureHtlcWithPreimage : PaymentDetailsDb() {
            @Serializable
            data class V0(val preimages: List<ByteVector32>) : FutureHtlcWithPreimage()
        }

        sealed class ChannelBalanceForFutureHtlc : PaymentDetailsDb() {
            @Serializable
            data class V0(val paymentHashes: List<ByteVector32>) : ChannelBalanceForFutureHtlc()
        }

        companion object {

            fun PaymentDetailsDb.asOfficial(): LiquidityAds.PaymentDetails = when (this) {
                is ChannelBalance.V0 -> LiquidityAds.PaymentDetails.FromChannelBalance
                is FutureHtlc.V0 -> LiquidityAds.PaymentDetails.FromFutureHtlc(this.paymentHashes)
                is FutureHtlcWithPreimage.V0 -> LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage(this.preimages)
                is ChannelBalanceForFutureHtlc.V0 -> LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(this.paymentHashes)
            }

            fun LiquidityAds.PaymentDetails.asDb(): PaymentDetailsDb = when (this) {
                is LiquidityAds.PaymentDetails.FromChannelBalance -> ChannelBalance.V0
                is LiquidityAds.PaymentDetails.FromFutureHtlc -> FutureHtlc.V0(this.paymentHashes)
                is LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage -> FutureHtlcWithPreimage.V0(this.preimages)
                is LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc -> ChannelBalanceForFutureHtlc.V0(this.paymentHashes)
            }
        }
    }

    sealed class PurchaseDb {

        sealed class Standard : PurchaseDb() {
            @Serializable
            data class V0(
                val amount: Satoshi,
                val miningFees: Satoshi,
                val serviceFee: Satoshi,
                val paymentDetails: PaymentDetailsDb
            ) : Standard()
        }

        sealed class WithFeeCredit : PurchaseDb() {
            @Serializable
            data class V0(
                val amount: Satoshi,
                val miningFees: Satoshi,
                val serviceFee: Satoshi,
                val paymentDetails: PaymentDetailsDb,
                val feeCreditUsed: MilliSatoshi
            ) : WithFeeCredit()
        }

        companion object {

            fun PurchaseDb.asOfficial(): LiquidityAds.Purchase = when (val data = this) {
                is Standard.V0 -> LiquidityAds.Purchase.Standard(
                    amount = data.amount,
                    fees = LiquidityAds.Fees(miningFee = data.miningFees, serviceFee = data.serviceFee),
                    paymentDetails = data.paymentDetails.asOfficial()
                )
                is WithFeeCredit.V0 -> LiquidityAds.Purchase.WithFeeCredit(
                    amount = data.amount,
                    fees = LiquidityAds.Fees(miningFee = data.miningFees, serviceFee = data.serviceFee),
                    feeCreditUsed = data.feeCreditUsed,
                    paymentDetails = data.paymentDetails.asOfficial()
                )
            }

            fun LiquidityAds.Purchase.asDb(): PurchaseDb = when (val value = this) {
                is LiquidityAds.Purchase.Standard -> Standard.V0(value.amount, value.fees.miningFee, value.fees.serviceFee, value.paymentDetails.asDb())
                is LiquidityAds.Purchase.WithFeeCredit -> WithFeeCredit.V0(value.amount, value.fees.miningFee, value.fees.serviceFee, value.paymentDetails.asDb(), value.feeCreditUsed)
            }
        }
    }
}