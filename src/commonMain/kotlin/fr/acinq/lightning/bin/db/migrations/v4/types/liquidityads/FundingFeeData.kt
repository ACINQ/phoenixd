@file:UseSerializers(
    MilliSatoshiSerializer::class,
    TxIdSerializer::class,
)

package fr.acinq.lightning.bin.db.migrations.v4.types.liquidityads

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.migrations.v3.json.MilliSatoshiSerializer
import fr.acinq.lightning.bin.db.migrations.v3.json.TxIdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
sealed class FundingFeeData {

    @Serializable
    @SerialName("fr.acinq.phoenix.db.payments.liquidityads.FundingFeeData.V0")
    data class V0(val amount: MilliSatoshi, val fundingTxId: TxId) : FundingFeeData()

}