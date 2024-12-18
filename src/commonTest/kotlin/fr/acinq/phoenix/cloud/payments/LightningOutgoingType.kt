package fr.acinq.phoenix.db.cloud

import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingDetailsData
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingDetailsTypeVersion
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingStatusData
import fr.acinq.lightning.bin.db.migrations.v4.types.LightningOutgoingStatusTypeVersion
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LightningOutgoingPaymentWrapper(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val msat: Long,
    @ByteString
    val recipient: ByteArray,
    val details: DetailsWrapper,
    val parts: List<LightningOutgoingPartWrapper>,
    // these parts are now obsolete, we now use a dedicated object for channels closing
    val closingTxsParts: List<LightningOutgoingClosingTxPartWrapper> = emptyList(),
    val status: StatusWrapper?,
    val createdAt: Long
) {
    /**
     * Unwraps a cbor-serialized outgoing payment. Should return a [LightningOutgoingPayment], but on may also return a
     * [ChannelCloseOutgoingPayment] in case the data are legacy and actually contain data for a channel closing.
     */
    @Throws(Exception::class)
    fun unwrap(): OutgoingPayment? {
        val details = details.unwrap()
        return if (details != null) {
            val status = status?.unwrap() ?: LightningOutgoingPayment.Status.Pending
            val parts = parts.map { it.unwrap() }
            LightningOutgoingPayment(
                id = id,
                recipientAmount = msat.msat,
                recipient = PublicKey.parse(recipient),
                status = status,
                parts = parts,
                details = details,
                createdAt = createdAt
            )
        } else {
           null
        }
    }

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class DetailsWrapper(
        val type: String,
        @ByteString
        val blob: ByteArray
    ) {

        fun unwrap(): LightningOutgoingPayment.Details? {
            return LightningOutgoingDetailsData.deserialize(
                typeVersion = LightningOutgoingDetailsTypeVersion.valueOf(type),
                blob = blob
            )
        }
    } // </DetailsWrapper>

    @Serializable
    @OptIn(ExperimentalSerializationApi::class)
    data class StatusWrapper(
        val ts: Long,
        val type: String,
        @ByteString
        val blob: ByteArray
    ) {

        fun unwrap(): LightningOutgoingPayment.Status {
            return LightningOutgoingStatusData.deserialize(
                typeVersion = LightningOutgoingStatusTypeVersion.valueOf(type),
                blob = blob,
                completedAt = ts
            )
        }

    } // </StatusWrapper>

    companion object
} // </OutgoingPaymentWrapper>
