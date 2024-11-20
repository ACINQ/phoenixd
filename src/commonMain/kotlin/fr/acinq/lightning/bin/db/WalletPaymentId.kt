package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.bin.deriveUUID
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.UUID

/**
 * Helper class that helps to link an actual payment to a unique string. This is useful to store a reference
 * to a payment in a single TEXT sql column.
 *
 * e.g.: incoming|2cd7edf6-de67-46a0-bbea-6f300741c9a4
 * This is a unique identifier for an [IncomingPayment] with id=2cd7edf6-...1c9a4.
 *
 * It is common to reference these rows in other database tables via [dbType] or [dbId].
 *
 * @param dbType Long identifies the table in which the payment is stored (incoming/outgoing/splice-outgoing/...)
 * @param dbId String identifier inside the table.
 */
sealed class WalletPaymentId {

    abstract val dbType: DbType
    abstract val dbId: String

    /** Use this to get a single (hashable) identifier for the row, for example within a hashmap or Cache. */
    abstract val identifier: String

    data class IncomingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.INCOMING
        override val dbId: String = id.toString()
        override val identifier: String = "incoming|$dbId"

        companion object {
            fun fromString(id: String) = IncomingPaymentId(id = UUID.fromString(id))
            /** For backward compatibility purposes (we used to use the `paymentHash` as identifier for incoming payments) */
            fun fromPaymentHash(paymentHash: ByteVector32) = IncomingPaymentId(id = paymentHash.deriveUUID())
        }
    }

    data class LightningOutgoingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.OUTGOING
        override val dbId: String = id.toString()
        override val identifier: String = "outgoing|$dbId"

        companion object {
            fun fromString(id: String) = LightningOutgoingPaymentId(id = UUID.fromString(id))
        }
    }

    data class SpliceOutgoingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.SPLICE_OUTGOING
        override val dbId: String = id.toString()
        override val identifier: String = "splice_outgoing|$dbId"

        companion object {
            fun fromString(id: String) = SpliceOutgoingPaymentId(id = UUID.fromString(id))
        }
    }

    data class ChannelCloseOutgoingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.CHANNEL_CLOSE_OUTGOING
        override val dbId: String = id.toString()
        override val identifier: String = "channel_close_outgoing|$dbId"

        companion object {
            fun fromString(id: String) = ChannelCloseOutgoingPaymentId(id = UUID.fromString(id))
        }
    }

    data class SpliceCpfpOutgoingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.SPLICE_CPFP_OUTGOING
        override val dbId: String = id.toString()
        override val identifier: String = "splice_cpfp_outgoing|$dbId"

        companion object {
            fun fromString(id: String) = SpliceCpfpOutgoingPaymentId(id = UUID.fromString(id))
        }
    }

    data class InboundLiquidityOutgoingPaymentId(val id: UUID) : WalletPaymentId() {
        override val dbType: DbType = DbType.INBOUND_LIQUIDITY_OUTGOING
        override val dbId: String = id.toString()
        override val identifier: String = "inbound_liquidity_outgoing|$dbId"

        companion object {
            fun fromString(id: String) = InboundLiquidityOutgoingPaymentId(id = UUID.fromString(id))
        }
    }

    enum class DbType(val value: Long) {
        INCOMING(1),
        OUTGOING(2),
        SPLICE_OUTGOING(3),
        CHANNEL_CLOSE_OUTGOING(4),
        SPLICE_CPFP_OUTGOING(5),
        INBOUND_LIQUIDITY_OUTGOING(6),
    }

    companion object {
        fun create(type: Long, id: String): WalletPaymentId? {
            return when (type) {
                DbType.INCOMING.value -> IncomingPaymentId.fromString(id)
                DbType.OUTGOING.value -> LightningOutgoingPaymentId.fromString(id)
                DbType.SPLICE_OUTGOING.value -> SpliceOutgoingPaymentId.fromString(id)
                DbType.CHANNEL_CLOSE_OUTGOING.value -> ChannelCloseOutgoingPaymentId.fromString(id)
                DbType.SPLICE_CPFP_OUTGOING.value -> SpliceCpfpOutgoingPaymentId.fromString(id)
                DbType.INBOUND_LIQUIDITY_OUTGOING.value -> InboundLiquidityOutgoingPaymentId.fromString(id)
                else -> null
            }
        }
    }
}

fun WalletPayment.walletPaymentId(): WalletPaymentId = when (this) {
    is IncomingPayment -> WalletPaymentId.IncomingPaymentId(id = this.id)
    is LightningOutgoingPayment -> WalletPaymentId.LightningOutgoingPaymentId(id = this.id)
    is SpliceOutgoingPayment -> WalletPaymentId.SpliceOutgoingPaymentId(id = this.id)
    is ChannelCloseOutgoingPayment -> WalletPaymentId.ChannelCloseOutgoingPaymentId(id = this.id)
    is SpliceCpfpOutgoingPayment -> WalletPaymentId.SpliceCpfpOutgoingPaymentId(id = this.id)
    is InboundLiquidityOutgoingPayment -> WalletPaymentId.InboundLiquidityOutgoingPaymentId(id = this.id)
}
