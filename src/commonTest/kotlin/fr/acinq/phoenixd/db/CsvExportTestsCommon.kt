package fr.acinq.phoenixd.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenixd.createAppDbDriver
import fr.acinq.phoenixd.csv.WalletPaymentCsvWriter
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class CsvExportTestsCommon {

    @Test
    fun `export to csv`() = runBlocking {
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
        val paymentsDb = SqlitePaymentsDb(database)
        val csvWriter = WalletPaymentCsvWriter(Path(testdir, "export.csv"))
        paymentsDb.processSuccessfulPayments(0, currentTimestampMillis()) { payment ->
            csvWriter.add(payment)
        }
        csvWriter.close()
    }

}