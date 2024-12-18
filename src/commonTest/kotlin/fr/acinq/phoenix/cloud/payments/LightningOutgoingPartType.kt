package fr.acinq.phoenix.db.cloud

import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.migrations.v4.queries.LightningOutgoingQueries
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingPartStatusData
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingPartStatusTypeVersion
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.utils.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString


/** Legacy object used when channel closing were stored as outgoing-payments parts. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LightningOutgoingClosingTxPartWrapper(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @ByteString val txId: ByteArray,
    val sat: Long,
    val info: ClosingInfoWrapper,
    val createdAt: Long
) {
    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class ClosingInfoWrapper(
        val type: String,
        @ByteString val blob: ByteArray
    )
}

@Serializable
data class LightningOutgoingPartWrapper(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val msat: Long,
    val route: String,
    val status: StatusWrapper?,
    val createdAt: Long
) {

    fun unwrap() = LightningOutgoingPayment.Part(
        id = id,
        amount = MilliSatoshi(msat = msat),
        route = LightningOutgoingQueries.hopDescAdapter.decode(route),
        status = status?.unwrap() ?: LightningOutgoingPayment.Part.Status.Pending,
        createdAt = createdAt
    )

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class StatusWrapper(
        val ts: Long, // timestamp: completedAt
        val type: String,
        @ByteString
        val blob: ByteArray
    ) {
        fun unwrap(): LightningOutgoingPayment.Part.Status {
            return LightningOutgoingPartStatusData.deserialize(
                typeVersion = LightningOutgoingPartStatusTypeVersion.valueOf(type),
                blob = blob,
                completedAt = ts
            )
        }
    } // </StatusWrapper>

} // </OutgoingPartData>
