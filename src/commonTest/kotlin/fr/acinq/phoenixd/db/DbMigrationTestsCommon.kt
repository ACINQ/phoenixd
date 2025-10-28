package fr.acinq.phoenixd.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.phoenixd.createAppDbDriver
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DbMigrationTestsCommon {

    @Test
    fun `read v1 db`() = runBlocking {
        val testdir = Path(SystemTemporaryDirectory, "phoenix_tests", "phoenix_testdb_${Clock.System.now().toEpochMilliseconds()}")
        SystemFileSystem.createDirectories(testdir)
        SystemFileSystem.list(Path("src/commonTest/resources/sampledbs/v1")).forEach { file ->
            SystemFileSystem.source(file).buffered().use { bytesIn ->
                SystemFileSystem.sink(Path(testdir, file.name)).buffered().use { bytesOut ->
                    bytesIn.transferTo(bytesOut)
                }
            }
        }
        val driver = createAppDbDriver(testdir, Chain.Mainnet, PublicKey.fromHex("0326930365cec7d84e9ae08810165ac6731fa01e8d91459f6fec2f5e8ca7e8a4f7"))
        val database = createPhoenixDb(driver)

        SqlitePaymentsDb(database)
            .listIncomingPayments(from = 0L, to = Long.MAX_VALUE, limit = Long.MAX_VALUE, offset = 0L, listAll = true)
            .also { assertEquals(1, it.size) }

        driver.close()
    }

    @Test
    fun `read v3 db`() = runBlocking {
        val testdir = Path(SystemTemporaryDirectory, "phoenix_tests", "phoenix_testdb_${Clock.System.now().toEpochMilliseconds()}")
        SystemFileSystem.createDirectories(testdir)
        SystemFileSystem.list(Path("src/commonTest/resources/sampledbs/v3")).forEach { file ->
            SystemFileSystem.source(file).buffered().use { bytesIn ->
                SystemFileSystem.sink(Path(testdir, file.name)).buffered().use { bytesOut ->
                    bytesIn.transferTo(bytesOut)
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

        driver.close()
    }


}