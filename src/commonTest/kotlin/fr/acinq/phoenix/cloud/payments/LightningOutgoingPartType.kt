package fr.acinq.phoenix.db.cloud

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
    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class StatusWrapper(
        val ts: Long, // timestamp: completedAt
        val type: String,
        @ByteString
        val blob: ByteArray
    )

} // </OutgoingPartData>
