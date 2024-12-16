package fr.acinq.lightning.bin.db.migrations.v3

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import fr.acinq.lightning.bin.db.migrations.v3.types.mapIncomingPaymentFromV3
import fr.acinq.lightning.bin.deriveUUID
import fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment
import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.serialization.payment.Serialization

val AfterVersion3 = AfterVersion(3) { driver ->

    val transacter = object : TransacterImpl(driver) {}

    transacter.transaction {
        val payments = driver.executeQuery(
            identifier = null,
            sql = "SELECT * FROM incoming_payments",
            parameters = 0,
            mapper = { cursor ->
                val result = buildList {
                    while (cursor.next().value) {
                        val o = mapIncomingPaymentFromV3(
                            payment_hash = cursor.getBytes(0)!!,
                            preimage = cursor.getBytes(1)!!,
                            created_at = cursor.getLong(2)!!,
                            origin_type = cursor.getString(3)!!,
                            origin_blob = cursor.getBytes(4)!!,
                            received_amount_msat = cursor.getLong(5),
                            received_at = cursor.getLong(6),
                            received_with_type = cursor.getString(7),
                            received_with_blob = cursor.getBytes(8),
                        )
                        add(o)
                    }
                }
                QueryResult.Value(result)
            }
        ).value

        listOf(
            "DROP TABLE incoming_payments",
            """
                CREATE TABLE incoming_payments (
                    id TEXT NOT NULL PRIMARY KEY,
                    payment_hash BLOB,
                    created_at INTEGER NOT NULL,
                    received_at INTEGER DEFAULT NULL,
                    data BLOB NOT NULL
                )
            """.trimIndent(),
            "CREATE INDEX incoming_payments_payment_hash_idx ON incoming_payments(payment_hash)",
        ).forEach { sql ->
            driver.execute(identifier = null, sql = sql, parameters = 0)
        }

        payments
            .forEach { payment ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO incoming_payments (id, payment_hash, created_at, received_at, data) VALUES (?, ?, ?, ?, ?)",
                    parameters = 5
                ) {
                    println("migrating incoming $payment")
                    when (payment) {
                        is LightningIncomingPayment -> {
                            bindString(0, payment.paymentHash.deriveUUID().toString())
                            bindBytes(1, payment.paymentHash.toByteArray())
                        }
                        is @Suppress("DEPRECATION") LegacyPayToOpenIncomingPayment -> {
                            bindString(0, payment.paymentHash.deriveUUID().toString())
                            bindBytes(1, payment.paymentHash.toByteArray())
                        }
                        else -> TODO("unsupported payment=$payment")
                    }
                    bindLong(2, payment.createdAt)
                    bindLong(3, payment.completedAt)
                    bindBytes(4, Serialization.serialize(payment))
                }
            }

        val paymentHashLinks = driver.executeQuery(
            identifier = null,
            sql = """
                SELECT payments.id, lower(hex(payments.payment_hash))
                FROM link_tx_to_payments link
                JOIN incoming_payments payments ON link.id=lower(hex(payments.payment_hash))
                WHERE link.type=1
            """.trimIndent(),
            parameters = 0,
            mapper = { cursor ->
                val result = buildList {
                    while (cursor.next().value) {
                        val id = cursor.getString(0)!!
                        val payment_hash = cursor.getString(1)!!
                        add(payment_hash to id)
                    }
                }
                QueryResult.Value(result)
            }
        ).value

        paymentHashLinks
            .forEach { (paymentHash, id) ->
                driver.execute(
                    identifier = null,
                    sql = """
                        UPDATE link_tx_to_payments
                        SET id=?
                        WHERE id=?
                    """.trimIndent(),
                    parameters = 2
                ) {
                    bindString(0, id)
                    bindString(1, paymentHash)
                }
            }

        val metadataLinks = driver.executeQuery(
            identifier = null,
            sql = """
                SELECT payments.id, lower(hex(payments.payment_hash))
                FROM payments_metadata meta
                JOIN incoming_payments payments ON meta.id=lower(hex(payments.payment_hash))
                WHERE meta.type=1
            """.trimIndent(),
            parameters = 0,
            mapper = { cursor ->
                val result = buildList {
                    while (cursor.next().value) {
                        val id = cursor.getString(0)!!
                        val payment_hash = cursor.getString(1)!!
                        add(payment_hash to id)
                    }
                }
                QueryResult.Value(result)
            }
        ).value

        metadataLinks
            .forEach { (paymentHash, id) ->
                driver.execute(
                    identifier = null,
                    sql = """
                        UPDATE payments_metadata
                        SET id=?
                        WHERE id=?
                    """.trimIndent(),
                    parameters = 2
                ) {
                    bindString(0, id)
                    bindString(1, paymentHash)
                }
            }
    }
}