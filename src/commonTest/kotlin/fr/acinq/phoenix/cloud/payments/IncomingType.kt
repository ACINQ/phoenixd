package fr.acinq.phoenix.db.cloud

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class IncomingPaymentWrapper(
    @ByteString
    val preimage: ByteArray,
    val origin: OriginWrapper,
    val received: ReceivedWrapper?,
    val createdAt: Long
) {

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class OriginWrapper(
        val type: String,
        @ByteString
        val blob: ByteArray
    )

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class ReceivedWrapper(
        val ts: Long, // timestamp / receivedAt
        val type: String,
        @ByteString
        val blob: ByteArray
    )
}
