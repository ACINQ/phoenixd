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
import kotlin.test.assertEquals

class DbMigrationTestsCommon {

    @Test
    fun `read v3 db`() = runTest {
        val testdir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "phoenix_tests" / "phoenix_testdb_${Clock.System.now().toEpochMilliseconds()}"
        FileSystem.SYSTEM.createDirectories(testdir)
        FileSystem.RESOURCES.list("/sampledbs/v3".toPath()).forEach { file ->
            FileSystem.RESOURCES.source(file).use { bytesIn ->
                FileSystem.SYSTEM.sink(testdir / file.name).buffer().use { bytesOut ->
                    bytesOut.writeAll(bytesIn)
                }
            }
        }
        val driver = createAppDbDriver(testdir, Chain.Testnet3, PublicKey.fromHex("03be9f16ffcfe10ec7381506601b1b75c9638d5e5aa8c4e7a546573ee09bc68fa2"))
        val database = createPhoenixDb(driver)

        SqlitePaymentsDb(database)
            .listIncomingPayments(from = 0L, to = Long.MAX_VALUE, limit = Long.MAX_VALUE, offset = 0L, listAll = true)
            .also { assertEquals(2150, it.size) }

        SqlitePaymentsDb(database)
            .listIncomingPayments(from = 0L, to = Long.MAX_VALUE, limit = Long.MAX_VALUE, offset = 0L, listAll = false)
            .also { assertEquals(739, it.size) }

        SqlitePaymentsDb(database)
            .listOutgoingPayments(from = 0L, to = Long.MAX_VALUE, limit = Long.MAX_VALUE, offset = 0L, listAll = true)
            .also { assertEquals(2 + 1 + 30 + 5, it.size) }

        SqlitePaymentsDb(database)
            .listOutgoingPayments(from = 0L, to = Long.MAX_VALUE, limit = Long.MAX_VALUE, offset = 0L, listAll = false)
            .also { assertEquals(2 + 1 + 24 + 5, it.size) }
    }


}