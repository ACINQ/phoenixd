@file:Suppress("DEPRECATION")

package fr.acinq.lightning.bin.db.migrations.v4

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import fr.acinq.lightning.bin.db.payments.*
import fr.acinq.lightning.bin.db.payments.LightningOutgoingQueries.Companion.hopDescAdapter
import fr.acinq.lightning.bin.toByteArray
import fr.acinq.lightning.db.*
import fr.acinq.lightning.serialization.payment.Serialization

val AfterVersion4 = AfterVersion(4) { driver ->

    val transacter = object : TransacterImpl(driver) {}

    fun insertPayment(payment: WalletPayment) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO outgoing_payments (id, payment_hash, tx_id, created_at, completed_at, data) VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6
        ) {
            val (paymentHash, txId) = when (payment) {
                is LightningIncomingPayment -> payment.paymentHash to null
                is OnChainIncomingPayment -> null to payment.txId
                is LegacyPayToOpenIncomingPayment -> payment.paymentHash to null
                is LegacySwapInIncomingPayment -> null to null
                is LightningOutgoingPayment -> payment.paymentHash to null
                is OnChainOutgoingPayment -> null to payment.txId
            }
            bindBytes(0, payment.id.toByteArray())
            bindBytes(1, paymentHash?.toByteArray())
            bindBytes(2, txId?.value?.toByteArray())
            bindLong(3, payment.createdAt)
            bindLong(4, payment.completedAt)
            bindBytes(5, Serialization.serialize(payment))
        }
    }

    transacter.transaction {
        driver.executeQuery(
            identifier = null,
            sql = """
    |SELECT parent.id,
    |       parent.recipient_amount_msat,
    |       parent.recipient_node_id,
    |       parent.payment_hash,
    |       parent.details_type,
    |       parent.details_blob,
    |       parent.created_at,
    |       parent.completed_at,
    |       parent.status_type,
    |       parent.status_blob,
    |       -- lightning parts
    |       lightning_parts.part_id AS lightning_part_id,
    |       lightning_parts.part_amount_msat AS lightning_part_amount_msat,
    |       lightning_parts.part_route AS lightning_part_route,
    |       lightning_parts.part_created_at AS lightning_part_created_at,
    |       lightning_parts.part_completed_at AS lightning_part_completed_at,
    |       lightning_parts.part_status_type AS lightning_part_status_type,
    |       lightning_parts.part_status_blob AS lightning_part_status_blob
    |FROM lightning_outgoing_payments AS parent
    |LEFT OUTER JOIN lightning_outgoing_payment_parts AS lightning_parts ON lightning_parts.part_parent_id = parent.id
    """.trimMargin(),
            parameters = 0,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val payment = LightningOutgoingQueries.mapLightningOutgoingPayment(
                        cursor.getString(0)!!,
                        cursor.getLong(1)!!,
                        cursor.getString(2)!!,
                        cursor.getBytes(3)!!,
                        LightningOutgoingDetailsTypeVersion.valueOf(cursor.getString(4)!!),
                        cursor.getBytes(5)!!,
                        cursor.getLong(6)!!,
                        cursor.getLong(7),
                        cursor.getString(8)?.let { LightningOutgoingStatusTypeVersion.valueOf(it) },
                        cursor.getBytes(9),
                        cursor.getString(10),
                        cursor.getLong(11),
                        cursor.getString(12)?.let { hopDescAdapter.decode(it) },
                        cursor.getLong(13),
                        cursor.getLong(14),
                        cursor.getString(15)?.let { LightningOutgoingPartStatusTypeVersion.valueOf(it) },
                        cursor.getBytes(16)
                    )
                    insertPayment(payment)
                }
                QueryResult.Unit
            }
        )

        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, mining_fees_sat, channel_id, tx_id, lease_type, lease_blob, payment_details_type, created_at, confirmed_at, locked_at FROM inbound_liquidity_outgoing_payments",
            parameters = 0,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val payment = InboundLiquidityQueries.mapPayment(
                        id = cursor.getString(0)!!,
                        mining_fees_sat = cursor.getLong(1)!!,
                        channel_id = cursor.getBytes(2)!!,
                        tx_id = cursor.getBytes(3)!!,
                        lease_type = cursor.getString(4)!!,
                        lease_blob = cursor.getBytes(5)!!,
                        payment_details_type = cursor.getString(6),
                        created_at = cursor.getLong(7)!!,
                        confirmed_at = cursor.getLong(8),
                        locked_at = cursor.getLong(9)
                    )
                    insertPayment(payment)
                }
                QueryResult.Unit
            }
        )

        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, recipient_amount_sat, address, mining_fees_sat, tx_id, channel_id, created_at, confirmed_at, locked_at FROM splice_outgoing_payments",
            parameters = 0,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val payment = SpliceOutgoingQueries.mapSpliceOutgoingPayment(
                        id = cursor.getString(0)!!,
                        recipient_amount_sat = cursor.getLong(1)!!,
                        address = cursor.getString(2)!!,
                        mining_fees_sat = cursor.getLong(3)!!,
                        tx_id = cursor.getBytes(4)!!,
                        channel_id = cursor.getBytes(5)!!,
                        created_at = cursor.getLong(6)!!,
                        confirmed_at = cursor.getLong(7),
                        locked_at = cursor.getLong(8)
                    )
                    insertPayment(payment)
                }
                QueryResult.Unit
            }
        )

        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, mining_fees_sat, channel_id, tx_id, created_at, confirmed_at, locked_at FROM splice_cpfp_outgoing_payments",
            parameters = 0,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val payment = SpliceCpfpOutgoingQueries.mapCpfp(
                        id = cursor.getString(0)!!,
                        mining_fees_sat = cursor.getLong(1)!!,
                        channel_id = cursor.getBytes(2)!!,
                        tx_id = cursor.getBytes(3)!!,
                        created_at = cursor.getLong(4)!!,
                        confirmed_at = cursor.getLong(5),
                        locked_at = cursor.getLong(6)
                    )
                    insertPayment(payment)
                }
                QueryResult.Unit
            }
        )

        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, recipient_amount_sat, address, is_default_address, mining_fees_sat, tx_id, created_at, confirmed_at, locked_at, channel_id, closing_info_type, closing_info_blob FROM channel_close_outgoing_payments",
            parameters = 0,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val payment = ChannelCloseOutgoingQueries.mapChannelCloseOutgoingPayment(
                        id = cursor.getString(0)!!,
                        amount_sat = cursor.getLong(1)!!,
                        address = cursor.getString(2)!!,
                        mining_fees_sat = cursor.getLong(3)!!,
                        is_default_address = cursor.getLong(4)!!,
                        tx_id = cursor.getBytes(5)!!,
                        created_at = cursor.getLong(6)!!,
                        confirmed_at = cursor.getLong(7),
                        locked_at = cursor.getLong(8),
                        channel_id = cursor.getBytes(9)!!,
                        closing_info_type = ClosingInfoTypeVersion.valueOf(cursor.getString(10)!!),
                        closing_info_blob = cursor.getBytes(11)!!
                    )
                    insertPayment(payment)
                }
                QueryResult.Unit
            }
        )

        listOf(
            "DROP TABLE lightning_outgoing_payments",
            "DROP TABLE lightning_outgoing_payment_parts",
            "DROP TABLE inbound_liquidity_outgoing_payments",
            "DROP TABLE splice_outgoing_payments",
            "DROP TABLE splice_cpfp_outgoing_payments",
            "DROP TABLE channel_close_outgoing_payments",
        ).forEach { sql ->
            driver.execute(identifier = null, sql = sql, parameters = 0)
        }
    }
}