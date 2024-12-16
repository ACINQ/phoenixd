package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.createAppDbDriver
import fr.acinq.lightning.db.OutgoingPayment
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals

class DbMigrationTestsCommon {

    @Test
    fun `read v3 db`() = runTest {
        val testdir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "phoenix_tests" / "phoenix_testdb_${Clock.System.now().toEpochMilliseconds()}"
        println(testdir)
        FileSystem.SYSTEM.createDirectories(testdir)
        FileSystem.RESOURCES.list("/sampledbs/v3".toPath()).forEach { file ->
            println(file)
            FileSystem.RESOURCES.source(file).use { bytesIn ->
                FileSystem.SYSTEM.sink(testdir / file.name).buffer().use { bytesOut ->
                    bytesOut.writeAll(bytesIn)
                }
            }
        }
        val driver = createAppDbDriver(testdir, Chain.Testnet3, PublicKey.fromHex("03be9f16ffcfe10ec7381506601b1b75c9638d5e5aa8c4e7a546573ee09bc68fa2"))
        val database = createPhoenixDb(driver)
        val payments = SqlitePaymentsDb(database).listSuccessfulPayments()
        payments.forEach { println("$it") }
        val expectedLines =
            2 + // channel_close_outgoing_payments
                    1 + // inbound_liquidity_outgoing_payments
                    739 + // incoming_payments
                    24 + // lightning_outgoing_payments
                    5 // splice_outgoing_payments
        assertEquals(expectedLines, payments.size)
    }


}