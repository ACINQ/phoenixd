package fr.acinq.lightning.bin.utils

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import kotlinx.datetime.Instant
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer

open class CsvWriter(path: Path) {

    private val sink: BufferedSink

    init {
        path.parent?.let { dir -> FileSystem.SYSTEM.createDirectories(dir) }
        sink = FileSystem.SYSTEM.sink(path, mustCreate = false).buffer()
    }

    fun addRow(vararg fields: String) {
        val cleanFields = fields.map { processField(it) }
        sink.writeUtf8(cleanFields.joinToString(separator = ",", postfix = "\n"))
    }

    fun addRow(fields: List<String>) {
        addRow(*fields.toTypedArray())
    }

    private fun processField(str: String): String {
        return str.findAnyOf(listOf(",", "\"", "\n"))?.let {
            // - field must be enclosed in double-quotes
            // - a double-quote appearing inside the field must be
            //   escaped by preceding it with another double quote
            "\"${str.replace("\"", "\"\"")}\""
        } ?: str
    }

    fun close() {
        sink.flush()
        sink.close()
    }
}

class WalletPaymentCsvWriter(path: Path) : CsvWriter(path) {

    private val FIELD_DATE = "date"
    private val FIELD_TYPE = "type"
    private val FIELD_AMOUNT_MSAT = "amount_msat"
    private val FIELD_MINING_FEE_SAT = "mining_fee_sat"
    private val FIELD_SERVICE_FEE_MSAT = "service_fee_msat"
    private val FIELD_PAYMENT_HASH = "payment_hash"
    private val FIELD_TX_ID = "tx_id"

    init {
        addRow(FIELD_DATE, FIELD_TYPE, FIELD_AMOUNT_MSAT, FIELD_MINING_FEE_SAT, FIELD_SERVICE_FEE_MSAT, FIELD_PAYMENT_HASH, FIELD_TX_ID)
    }

    data class Details(
        val type: String,
        val miningFee: Satoshi,
        val serviceFee: MilliSatoshi,
        val paymentHash: ByteVector32?,
        val txId: TxId?
    )

    private fun addRow(
        timestamp: Long,
        amount: MilliSatoshi,
        details: Details
    ) {
        val dateStr = Instant.fromEpochMilliseconds(timestamp).toString() // ISO-8601 format
        addRow(dateStr, details.type, amount.msat.toString(), details.miningFee.sat.toString(), details.serviceFee.msat.toString(), details.paymentHash?.toHex() ?: "", details.txId?.toString() ?: "")
    }

    fun addRow(payment: WalletPayment) {
        val timestamp = payment.completedAt ?: payment.createdAt

        val details = when (payment) {
            is IncomingPayment -> when (val origin = payment.origin) {
                is IncomingPayment.Origin.Invoice -> Details("lightning_received", miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.Origin.SwapIn -> Details("legacy_swap_in", miningFee = payment.fees.truncateToSatoshi(), serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.Origin.OnChain -> Details("swap_in", miningFee = payment.fees.truncateToSatoshi(), serviceFee = 0.msat, paymentHash = null, txId = origin.txId)
                is IncomingPayment.Origin.Offer -> Details("lightning_received", miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null)
            }

            is LightningOutgoingPayment -> when (val details = payment.details) {
                is LightningOutgoingPayment.Details.Normal -> Details("lightning_sent", miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null)
                is LightningOutgoingPayment.Details.SwapOut -> Details("legacy_swap_out", miningFee = details.swapOutFee, serviceFee = 0.msat, paymentHash = null, txId = null)
                is LightningOutgoingPayment.Details.Blinded -> Details("lightning_sent", miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null)
            }

            is SpliceOutgoingPayment -> Details("splice_out", miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId)
            is ChannelCloseOutgoingPayment -> Details("channel_close", miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId)
            is SpliceCpfpOutgoingPayment -> Details("fee_bumping", miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId)
            is InboundLiquidityOutgoingPayment -> Details("liquidity", miningFee = payment.miningFees, serviceFee = payment.serviceFees.toMilliSatoshi(), paymentHash = null, txId = payment.txId)
        }

        val isOutgoing = payment is OutgoingPayment

        addRow(
            timestamp = timestamp,
            amount = if (isOutgoing) -payment.amount else payment.amount,
            details = details
        )
    }
}
