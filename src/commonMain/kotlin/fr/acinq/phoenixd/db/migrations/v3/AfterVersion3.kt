@file:Suppress("DEPRECATION")

package fr.acinq.phoenixd.db.migrations.v3

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import fr.acinq.phoenixd.db.migrations.v3.types.mapIncomingPaymentFromV3
import fr.acinq.phoenixd.deriveUUID
import fr.acinq.phoenixd.toByteArray
import fr.acinq.lightning.db.LegacyPayToOpenIncomingPayment
import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.serialization.payment.Serialization

/**
 * @param addEnclosingTransaction this is a workaround while waiting for
 * https://github.com/sqldelight/sqldelight/pull/5218 to be released to
 * be released.
 */
fun afterVersion3(addEnclosingTransaction: Boolean) = AfterVersion(3) { driver ->

    fun maybeTx(body: () -> Any) = if (addEnclosingTransaction) {
        val transacter = object : TransacterImpl(driver) {}
        transacter.transaction { body() }
    } else {
        body()
    }

    maybeTx {
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

        driver.execute(identifier = null, sql = "DROP TABLE incoming_payments", parameters = 0)

        payments
            .forEach { payment ->
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO payments_incoming (id, payment_hash, tx_id, created_at, received_at, data) VALUES (?, ?, ?, ?, ?, ?)",
                    parameters = 6
                ) {
                    when (payment) {
                        is LightningIncomingPayment -> {
                            bindBytes(0, payment.paymentHash.deriveUUID().toByteArray())
                            bindBytes(1, payment.paymentHash.toByteArray())
                            bindBytes(2, null)
                        }
                        is @Suppress("DEPRECATION") LegacyPayToOpenIncomingPayment -> {
                            bindBytes(0, payment.paymentHash.deriveUUID().toByteArray())
                            bindBytes(1, payment.paymentHash.toByteArray())
                            bindBytes(2, null)
                        }
                        else -> TODO("unsupported payment=$payment")
                    }
                    bindLong(3, payment.createdAt)
                    bindLong(4, payment.completedAt)
                    bindBytes(5, Serialization.serialize(payment))
                }
            }
    }
}