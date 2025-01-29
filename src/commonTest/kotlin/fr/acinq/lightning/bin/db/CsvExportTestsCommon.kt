package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.createAppDbDriver
import fr.acinq.lightning.bin.datadir
import fr.acinq.lightning.bin.csv.WalletPaymentCsvWriter
import fr.acinq.lightning.utils.currentTimestampMillis
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Ignore
import kotlin.test.Test

class CsvExportTestsCommon {

    @Test
    @Ignore
    fun `export to csv`() {
        val driver = createAppDbDriver(datadir, Chain.Testnet3, PublicKey.fromHex("0211dadf19b1268f1f21b0b233e22c4f648d419e2476bfd8fe356479fbad5c146d"))
        val database = createPhoenixDb(driver)
        val paymentsDb = SqlitePaymentsDb(database)
        val csvWriter = WalletPaymentCsvWriter("csv/export.csv".toPath())
        runBlocking {
            paymentsDb.processSuccessfulPayments(0, currentTimestampMillis()) { payment ->
                csvWriter.add(payment)
            }
        }
        csvWriter.close()
    }

    @Test
    fun `export to csv (starblocks)`() {
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
        val paymentsDb = SqlitePaymentsDb(database)
        val csvWriter = WalletPaymentCsvWriter("csv/export.csv".toPath())
        runBlocking {
            paymentsDb.processSuccessfulPayments(0, currentTimestampMillis()) { payment ->
                csvWriter.add(payment)
            }
        }
        csvWriter.close()
    }


}