package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.createAppDbDriver
import fr.acinq.lightning.bin.datadir
import fr.acinq.lightning.bin.csv.WalletPaymentCsvWriter
import fr.acinq.lightning.utils.currentTimestampMillis
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
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


}