package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.createAppDbDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Test

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
        val paymentsDb = SqlitePaymentsDb(database)
        val payments = paymentsDb.listIncomingPayments(0, Long.MAX_VALUE, Long.MAX_VALUE, 0, true)
        payments.forEach { println("${it.first.id} ${it.first}") }
        assert(payments.size == 2150)
    }


}