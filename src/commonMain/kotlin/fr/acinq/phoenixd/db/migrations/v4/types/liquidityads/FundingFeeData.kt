@file:UseSerializers(
    MilliSatoshiSerializer::class,
    TxIdSerializer::class,
)

package fr.acinq.phoenixd.db.migrations.v4.types.liquidityads

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.phoenixd.db.migrations.v3.json.MilliSatoshiSerializer
import fr.acinq.phoenixd.db.migrations.v3.json.TxIdSerializer
import fr.acinq.lightning.wire.LiquidityAds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
sealed class FundingFeeData {

    @Serializable
    @SerialName("fr.acinq.lightning.bin.db.payments.liquidityads.FundingFeeData.V0")
    data class V0(val amount: MilliSatoshi, val fundingTxId: TxId) : FundingFeeData()

    companion object {
        fun FundingFeeData.asCanonical(): LiquidityAds.FundingFee = when (this) {
            is V0 -> LiquidityAds.FundingFee(amount = amount, fundingTxId = fundingTxId)
        }
    }

}