package fr.acinq.lightning.bin.utils

import fr.acinq.lightning.db.*
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

class WalletPaymentCsvWriter(path: Path, val includesOriginDestination: Boolean = false) : CsvWriter(path) {

    private val FIELD_DATE = "Date"
    private val FIELD_AMOUNT_MSAT = "Amount Millisatoshi"
    private val FIELD_FEES_MSAT = "Fees Millisatoshi"
    private val FIELD_CONTEXT = "Context"

    init {
        val headers = buildList {
            add(FIELD_DATE)
            add(FIELD_AMOUNT_MSAT)
            add(FIELD_FEES_MSAT)
            if (includesOriginDestination) add(FIELD_CONTEXT)
        }
        addRow(headers)
    }

    fun addRow(payment: WalletPayment) {
        val fields = buildList {
            val date = payment.completedAt ?: payment.createdAt
            val dateStr = Instant.fromEpochMilliseconds(date).toString() // ISO-8601 format
            add(dateStr)

            val amtMsat = payment.amount.msat
            val feesMsat = payment.fees.msat
            val isOutgoing = payment is OutgoingPayment

            val amtMsatStr = if (isOutgoing) "-$amtMsat" else "$amtMsat"
            add(amtMsatStr)

            val feesMsatStr = if (feesMsat > 0) "-$feesMsat" else "$feesMsat"
            add(feesMsatStr)

            if (includesOriginDestination) {
                val details = when (payment) {
                    is IncomingPayment -> when (val origin = payment.origin) {
                        is IncomingPayment.Origin.Invoice -> "Incoming LN payment"
                        is IncomingPayment.Origin.SwapIn -> "Swap-in to ${origin.address ?: "N/A"}"
                        is IncomingPayment.Origin.OnChain -> "Swap-in with inputs: ${origin.localInputs.map { it.txid.toString() }}"
                        is IncomingPayment.Origin.Offer -> "Incoming offer ${origin.metadata.offerId}"
                    }

                    is LightningOutgoingPayment -> when (val details = payment.details) {
                        is LightningOutgoingPayment.Details.Normal -> "Outgoing LN payment to ${details.paymentRequest.nodeId.toHex()}"
                        is LightningOutgoingPayment.Details.SwapOut -> "Swap-out to ${details.address}"
                        is LightningOutgoingPayment.Details.Blinded -> "Offer to ${details.payerKey.publicKey()}"
                    }

                    is SpliceOutgoingPayment -> "Outgoing splice to ${payment.address}"
                    is ChannelCloseOutgoingPayment -> "Channel closing to ${payment.address}"
                    is SpliceCpfpOutgoingPayment -> "Accelerate transactions with CPFP"
                is InboundLiquidityOutgoingPayment -> "+${payment.purchase.amount.sat} sat inbound liquidity"
                }
                add(details)
            }
        }

        addRow(fields)
    }
}
