package fr.acinq.lightning.bin.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import fr.acinq.lightning.bin.db.payments.LightningOutgoingQueries
import fr.acinq.lightning.bin.toByteArray
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.serialization.payment.Serialization
import fr.acinq.lightning.utils.UUID
import fr.acinq.phoenix.db.*

fun createPhoenixDb(driver: SqlDriver) = PhoenixDatabase(
    driver = driver,
    incoming_paymentsAdapter = Incoming_payments.Adapter(IncomingPaymentAdapter),
    outgoing_paymentsAdapter = Outgoing_payments.Adapter(UUIDAdapter, OutgoingPaymentAdapter),
    link_lightning_outgoing_payment_partsAdapter = Link_lightning_outgoing_payment_parts.Adapter(UUIDAdapter, UUIDAdapter)
)

object IncomingPaymentAdapter : ColumnAdapter<IncomingPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): IncomingPayment = Serialization.deserialize(databaseValue).get() as IncomingPayment

    override fun encode(value: IncomingPayment): ByteArray = Serialization.serialize(value)
}

object OutgoingPaymentAdapter : ColumnAdapter<OutgoingPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): OutgoingPayment = Serialization.deserialize(databaseValue).get() as OutgoingPayment

    override fun encode(value: OutgoingPayment): ByteArray = Serialization.serialize(value)
}

object WalletPaymentAdapter : ColumnAdapter<WalletPayment, ByteArray> {
    override fun decode(databaseValue: ByteArray): WalletPayment = Serialization.deserialize(databaseValue).get()

    override fun encode(value: WalletPayment): ByteArray = Serialization.serialize(value)
}

object UUIDAdapter : ColumnAdapter<UUID, ByteArray> {
    override fun decode(databaseValue: ByteArray): UUID = UUID.fromBytes(databaseValue)

    override fun encode(value: UUID): ByteArray = value.toByteArray()

}

