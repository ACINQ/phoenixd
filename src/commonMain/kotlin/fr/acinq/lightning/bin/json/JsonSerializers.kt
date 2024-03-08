@file:UseSerializers(
    // This is used by Kotlin at compile time to resolve serializers (defined in this file)
    // in order to build serializers for other classes (also defined in this file).
    // If we used @Serializable annotations directly on the actual classes, Kotlin would be
    // able to resolve serializers by itself. It is verbose, but it allows us to contain
    // serialization code in this file.
    JsonSerializers.SatoshiSerializer::class,
    JsonSerializers.MilliSatoshiSerializer::class,
    JsonSerializers.ByteVector32Serializer::class,
    JsonSerializers.PublicKeySerializer::class,
    JsonSerializers.TxIdSerializer::class,
)

package fr.acinq.lightning.bin.json

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.json.JsonSerializers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

sealed class ApiType {

    @Serializable
    data class Channel internal constructor(
        val state: String,
        val channelId: ByteVector32? = null,
        val balanceSat: Satoshi? = null,
        val inboundLiquiditySat: Satoshi? = null,
        val capacitySat: Satoshi? = null,
        val fundingTxId: TxId? = null
    ) {
        companion object {
            fun from(channel: ChannelState) = when {
                channel is ChannelStateWithCommitments -> Channel(
                    state = channel.stateName,
                    channelId = channel.channelId,
                    balanceSat = channel.commitments.availableBalanceForSend().truncateToSatoshi(),
                    inboundLiquiditySat = channel.commitments.availableBalanceForReceive().truncateToSatoshi(),
                    capacitySat = channel.commitments.active.first().fundingAmount,
                    fundingTxId = channel.commitments.active.first().fundingTxId
                )
                else -> Channel(state = channel.stateName)
            }
        }
    }

    @Serializable
    data class NodeInfo(
        val nodeId: PublicKey,
        val channels: List<Channel>
    )

    @Serializable
    data class Balance(@SerialName("amountSat") val amount: Satoshi, @SerialName("feeCreditSat") val feeCredit: Satoshi) : ApiType()

    @Serializable
    data class GeneratedInvoice(@SerialName("amountSat") val amount: Satoshi?, val paymentHash: ByteVector32, val serialized: String) : ApiType()

    @Serializable
    sealed class ApiEvent : ApiType()

    @Serializable
    @SerialName("payment_received")
    data class PaymentReceived(@SerialName("amountSat") val amount: Satoshi, val paymentHash: ByteVector32) : ApiEvent() {
        constructor(event: fr.acinq.lightning.PaymentEvents.PaymentReceived) : this(event.amount.truncateToSatoshi(), event.paymentHash)
    }

    @Serializable
    @SerialName("payment_sent")
    data class PaymentSent(@SerialName("recipientAmountSat") val recipientAmount: Satoshi, @SerialName("routingFeeSat") val routingFee: Satoshi, val paymentHash: ByteVector32, val paymentPreimage: ByteVector32) : ApiEvent() {
        constructor(event: fr.acinq.lightning.io.PaymentSent) : this(
            event.payment.recipientAmount.truncateToSatoshi(),
            event.payment.routingFee.truncateToSatoshi(),
            event.payment.paymentHash,
            (event.payment.status as LightningOutgoingPayment.Status.Completed.Succeeded.OffChain).preimage
        )
    }

    @Serializable
    @SerialName("payment_failed")
    data class PaymentFailed(val paymentHash: ByteVector32, val reason: String) : ApiType() {
        constructor(event: fr.acinq.lightning.io.PaymentNotSent) : this(event.request.paymentHash, event.reason.reason.toString())
    }

}