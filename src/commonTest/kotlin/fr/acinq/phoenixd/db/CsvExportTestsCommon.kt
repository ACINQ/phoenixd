package fr.acinq.phoenixd.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenixd.createAppDbDriver
import fr.acinq.phoenixd.csv.WalletPaymentCsvWriter
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Test

class CsvExportTestsCommon {

    @Test
    fun `export to csv`() = runBlocking {
        val testdir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "phoenix_tests" / "phoenix_testdb_${Clock.System.now().toEpochMilliseconds()}"
        FileSystem.SYSTEM.createDirectories(testdir)
        FileSystem.SYSTEM.list("src/commonTest/resources/sampledbs/v3".toPath()).forEach { file ->
            FileSystem.SYSTEM.source(file).use { bytesIn ->
                FileSystem.SYSTEM.sink(testdir / file.name).buffer().use { bytesOut ->
                    bytesOut.writeAll(bytesIn)
                }
            }
        }
        val driver = createAppDbDriver(testdir, Chain.Testnet3, PublicKey.fromHex("03be9f16ffcfe10ec7381506601b1b75c9638d5e5aa8c4e7a546573ee09bc68fa2"))
        val database = createPhoenixDb(driver)
        val paymentsDb = SqlitePaymentsDb(database)
        val csvWriter = WalletPaymentCsvWriter(testdir / "export.csv".toPath())
        paymentsDb.processSuccessfulPayments(0, currentTimestampMillis()) { payment ->
            csvWriter.add(payment)
        }
        csvWriter.close()
    }

}