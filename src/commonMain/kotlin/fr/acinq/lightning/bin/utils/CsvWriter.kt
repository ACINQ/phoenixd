package fr.acinq.lightning.bin.utils

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.LiquidityAds
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
    private val FIELD_FEE_CREDIT_MSAT = "fee_credit_msat"
    private val FIELD_MINING_FEE_SAT = "mining_fee_sat"
    private val FIELD_SERVICE_FEE_MSAT = "service_fee_msat"
    private val FIELD_PAYMENT_HASH = "payment_hash"
    private val FIELD_TX_ID = "tx_id"

    init {
        addRow(FIELD_DATE, FIELD_TYPE, FIELD_AMOUNT_MSAT, FIELD_FEE_CREDIT_MSAT, FIELD_MINING_FEE_SAT, FIELD_SERVICE_FEE_MSAT, FIELD_PAYMENT_HASH, FIELD_TX_ID)
    }

    data class Details(
        val type: String,
        val amount: MilliSatoshi,
        val feeCredit: MilliSatoshi,
        val miningFee: Satoshi,
        val serviceFee: MilliSatoshi,
        val paymentHash: ByteVector32?,
        val txId: TxId?
    )

    private fun addRow(
        timestamp: Long,
        details: Details
    ) {
        val dateStr = Instant.fromEpochMilliseconds(timestamp).toString() // ISO-8601 format
        addRow(dateStr, details.type, details.amount.msat.toString(), details.feeCredit.msat.toString(), details.miningFee.sat.toString(), details.serviceFee.msat.toString(), details.paymentHash?.toHex() ?: "", details.txId?.toString() ?: "")
    }

    fun add(payment: WalletPayment) {
        val timestamp = payment.completedAt ?: payment.createdAt

        val details: List<Details> = when (payment) {
            is IncomingPayment -> when (val origin = payment.origin) {
                is IncomingPayment.Origin.Invoice -> extractLightningPaymentParts(payment)
                is IncomingPayment.Origin.SwapIn -> listOf(Details("legacy_swap_in", amount = payment.amount, feeCredit = 0.msat, miningFee = payment.fees.truncateToSatoshi(), serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null))
                is IncomingPayment.Origin.OnChain -> listOf(Details("swap_in", amount = payment.amount, feeCredit = 0.msat, miningFee = payment.fees.truncateToSatoshi(), serviceFee = 0.msat, paymentHash = null, txId = origin.txId))
                is IncomingPayment.Origin.Offer -> extractLightningPaymentParts(payment)
            }

            is LightningOutgoingPayment -> when (val details = payment.details) {
                is LightningOutgoingPayment.Details.Normal -> listOf(Details("lightning_sent", amount = -payment.amount, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null))
                is LightningOutgoingPayment.Details.SwapOut -> listOf(Details("legacy_swap_out", amount = -payment.amount, feeCredit = 0.msat, miningFee = details.swapOutFee, serviceFee = 0.msat, paymentHash = null, txId = null))
                is LightningOutgoingPayment.Details.Blinded -> listOf(Details("lightning_sent", amount = -payment.amount, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null))
            }

            is SpliceOutgoingPayment -> listOf(Details("splice_out", amount = payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is ChannelCloseOutgoingPayment -> listOf(Details("channel_close", amount = payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is SpliceCpfpOutgoingPayment -> listOf(Details("fee_bumping", amount = payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is InboundLiquidityOutgoingPayment -> listOf(Details("liquidity", amount = 0.msat, feeCredit = -((payment.purchase as? LiquidityAds.Purchase.WithFeeCredit)?.feeCreditUsed ?: 0.msat), miningFee = payment.miningFees, serviceFee = payment.serviceFees.toMilliSatoshi(), paymentHash = null, txId = payment.txId))
        }

        details.forEach { addRow(timestamp, it) }

    }

    private fun extractLightningPaymentParts(payment: IncomingPayment): List<Details> = payment.received?.receivedWith.orEmpty()
        .map {
            when (it) {
                is IncomingPayment.ReceivedWith.LightningPayment -> Details("lightning_received", amount = it.amountReceived, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.ReceivedWith.AddedToFeeCredit -> Details("fee_credit", amount = 0.msat, feeCredit = it.amountReceived, miningFee = 0.sat, serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.ReceivedWith.NewChannel -> Details("pay_to_open", amount = it.amountReceived, feeCredit = 0.msat, miningFee = it.miningFee, serviceFee = it.serviceFee, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.ReceivedWith.SpliceIn -> Details("pay_to_splice", amount = it.amountReceived, feeCredit = 0.msat, miningFee = it.miningFee, serviceFee = it.serviceFee, paymentHash = payment.paymentHash, txId = null)
                else -> error("unexpected receivedWith part $it")
            }
        }
        .groupBy { it.type }
        .values.map { parts ->
            Details(
                type = parts.first().type,
                amount = parts.map { it.amount }.sum(),
                feeCredit = parts.map { it.feeCredit }.sum(),
                miningFee = parts.map { it.miningFee }.sum(),
                serviceFee = parts.map { it.serviceFee }.sum(),
                paymentHash = parts.first().paymentHash,
                txId = parts.first().txId
            )
        }.toList()
}
