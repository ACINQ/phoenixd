package fr.acinq.lightning.bin.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.bin.toByteArray
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.serialization.payment.Serialization
import fr.acinq.lightning.utils.UUID
import fr.acinq.phoenix.db.*
import io.ktor.http.*

fun createPhoenixDb(driver: SqlDriver) = PhoenixDatabase(
    driver = driver,
    local_channelsAdapter = Local_channels.Adapter(ByteVector32Adapter, ChannelAdapter),
    htlc_infosAdapter = Htlc_infos.Adapter(ByteVector32Adapter, ByteVector32Adapter),
    payments_incomingAdapter = Payments_incoming.Adapter(UUIDAdapter, ByteVector32Adapter, TxIdAdapter, IncomingPaymentAdapter),
    payments_outgoingAdapter = Payments_outgoing.Adapter(UUIDAdapter, ByteVector32Adapter, TxIdAdapter, OutgoingPaymentAdapter),
    link_lightning_outgoing_payment_partsAdapter = Link_lightning_outgoing_payment_parts.Adapter(UUIDAdapter, UUIDAdapter),
    on_chain_txsAdapter = On_chain_txs.Adapter(UUIDAdapter, TxIdAdapter),
    payments_metadataAdapter = Payments_metadata.Adapter(UUIDAdapter, UrlAdapter)
)

object ChannelAdapter : ColumnAdapter<PersistedChannelState, ByteArray> {
    override fun decode(databaseValue: ByteArray): PersistedChannelState =
        (fr.acinq.lightning.serialization.channel.Serialization.deserialize(databaseValue) as fr.acinq.lightning.serialization.channel.Serialization.DeserializationResult.Success).state

    override fun encode(value: PersistedChannelState): ByteArray = fr.acinq.lightning.serialization.channel.Serialization.serialize(value)

}

object UUIDAdapter : ColumnAdapter<UUID, ByteArray> {
    override fun decode(databaseValue: ByteArray): UUID = UUID.fromBytes(databaseValue)

    override fun encode(value: UUID): ByteArray = value.toByteArray()
}

object ByteVector32Adapter : ColumnAdapter<ByteVector32, ByteArray> {
    override fun decode(databaseValue: ByteArray) = ByteVector32(databaseValue)

    override fun encode(value: ByteVector32): ByteArray = value.toByteArray()
}

object TxIdAdapter : ColumnAdapter<TxId, ByteArray> {
    override fun decode(databaseValue: ByteArray) = TxId(databaseValue)

    override fun encode(value: TxId): ByteArray = value.value.toByteArray()
}

object IncomingPaymentAdapter : ColumnAdapter<IncomingPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): IncomingPayment = Serialization.deserialize(databaseValue).getOrThrow() as IncomingPayment

    override fun encode(value: IncomingPayment): ByteArray = Serialization.serialize(value)
}

object OutgoingPaymentAdapter : ColumnAdapter<OutgoingPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): OutgoingPayment = Serialization.deserialize(databaseValue).getOrThrow() as OutgoingPayment

    override fun encode(value: OutgoingPayment): ByteArray = Serialization.serialize(value)
}

object WalletPaymentAdapter : ColumnAdapter<WalletPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): WalletPayment = Serialization.deserialize(databaseValue).getOrThrow()

    override fun encode(value: WalletPayment): ByteArray = Serialization.serialize(value)
}

object UrlAdapter : ColumnAdapter<Url, String> {
    override fun decode(databaseValue: String): Url = Url(databaseValue)

    override fun encode(value: Url): String = value.toString()

}


