package fr.acinq.lightning.bin.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import fr.acinq.lightning.bin.db.payments.LightningOutgoingQueries
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.serialization.payment.Serialization
import fr.acinq.phoenix.db.*

fun createPhoenixDb(driver: SqlDriver) = PhoenixDatabase(
    driver = driver,
    lightning_outgoing_payment_partsAdapter = Lightning_outgoing_payment_parts.Adapter(
        part_routeAdapter = LightningOutgoingQueries.hopDescAdapter,
        part_status_typeAdapter = EnumColumnAdapter()
    ),
    lightning_outgoing_paymentsAdapter = Lightning_outgoing_payments.Adapter(
        status_typeAdapter = EnumColumnAdapter(),
        details_typeAdapter = EnumColumnAdapter()
    ),
    incoming_paymentsAdapter = Incoming_payments.Adapter(object : ColumnAdapter<IncomingPayment, ByteArray> {
        override fun decode(databaseValue: ByteArray): IncomingPayment = Serialization.deserialize(databaseValue).get() as IncomingPayment

        override fun encode(value: IncomingPayment): ByteArray = Serialization.serialize(value)

    }),
    channel_close_outgoing_paymentsAdapter = Channel_close_outgoing_payments.Adapter(
        closing_info_typeAdapter = EnumColumnAdapter()
    ),
    paymentsAdapter = Payments.Adapter(object : ColumnAdapter<WalletPayment, ByteArray> {
        override fun decode(databaseValue: ByteArray): WalletPayment = Serialization.deserialize(databaseValue).get()

        override fun encode(value: WalletPayment): ByteArray = Serialization.serialize(value)

    })
)