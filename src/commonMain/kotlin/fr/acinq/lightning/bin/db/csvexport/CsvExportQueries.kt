package fr.acinq.lightning.bin.db.csvexport

import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.phoenix.db.PhoenixDatabase

class CsvExportQueries(val database: PhoenixDatabase) {
    private val csvExportQueries = database.csvExportQueries

    fun listSuccessfulPaymentIds(from: Long, to: Long, limit: Long, offset: Long): List<WalletPaymentId> {
        return csvExportQueries.listSuccessfulPaymentIds(startDate = from, endDate = to, limit = limit, offset = offset).executeAsList().mapNotNull {
            WalletPaymentId.create(it.type, it.id)
        }
    }
}