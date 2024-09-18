package fr.acinq.lightning.bin.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import fr.acinq.lightning.bin.db.payments.LightningOutgoingQueries
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
    incoming_paymentsAdapter = Incoming_payments.Adapter(
        origin_typeAdapter = EnumColumnAdapter(),
        received_with_typeAdapter = EnumColumnAdapter()
    ),
    channel_close_outgoing_paymentsAdapter = Channel_close_outgoing_payments.Adapter(
        closing_info_typeAdapter = EnumColumnAdapter()
    ),
)