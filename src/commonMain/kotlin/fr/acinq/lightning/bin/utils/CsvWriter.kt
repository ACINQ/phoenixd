package fr.acinq.lightning.bin.utils

import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.lightning.db.*
import kotlinx.datetime.Instant

object CsvWriter {

    data class Configuration(
        val includesOriginDestination: Boolean,
    )

    private const val FIELD_DATE = "Date"
    private const val FIELD_AMOUNT_MSAT = "Amount Millisatoshi"
    private const val FIELD_FEES_MSAT = "Fees Millisatoshi"
    private const val FIELD_CONTEXT = "Context"

    /**
     * Creates and returns the header row for the CSV file.
     * This includes the CRLF that terminates the row.
     */
    fun makeHeaderRow(config: Configuration): String {
        var header = "$FIELD_DATE,$FIELD_AMOUNT_MSAT,$FIELD_FEES_MSAT"
        if (config.includesOriginDestination) {
            header += ",$FIELD_CONTEXT"
        }

        header += "\r\n"
        return header
    }

    /**
     * Creates and returns the row for the given payment.
     * This includes the CRLF that terminates the row.
     *
     * @param payment Payment
     * @param config The configuration for the CSV file
     */
    fun makeRow(
        payment: WalletPayment,
        config: Configuration,
    ): String {

        val date = payment.completedAt ?: payment.createdAt
        val dateStr = Instant.fromEpochMilliseconds(date).toString() // ISO-8601 format
        var row = processField(dateStr)

        val amtMsat = payment.amount.msat
        val feesMsat = payment.fees.msat
        val isOutgoing = payment is OutgoingPayment

        val amtMsatStr = if (isOutgoing) "-$amtMsat" else "$amtMsat"
        row += ",${processField(amtMsatStr)}"

        val feesMsatStr = if (feesMsat > 0) "-$feesMsat" else "$feesMsat"
        row += ",${processField(feesMsatStr)}"

        if (config.includesOriginDestination) {
            val details = when (payment) {
                is IncomingPayment -> when (val origin = payment.origin) {
                    is IncomingPayment.Origin.Invoice -> "Incoming LN payment"
                    is IncomingPayment.Origin.SwapIn -> "Swap-in to ${origin.address ?: "N/A"}"
                    is IncomingPayment.Origin.OnChain -> {
                        "Swap-in with inputs: ${origin.localInputs.map { it.txid.toString() }}"
                    }

                    is IncomingPayment.Origin.Offer -> {
                        "Incoming offer ${origin.metadata.offerId}"
                    }
                }

                is LightningOutgoingPayment -> when (val details = payment.details) {
                    is LightningOutgoingPayment.Details.Normal -> "Outgoing LN payment to ${details.paymentRequest.nodeId.toHex()}"
                    is LightningOutgoingPayment.Details.SwapOut -> "Swap-out to ${details.address}"
                    is LightningOutgoingPayment.Details.Blinded -> "Offer to ${details.payerKey.publicKey()}"
                }

                is SpliceOutgoingPayment -> "Outgoing splice to ${payment.address}"
                is ChannelCloseOutgoingPayment -> "Channel closing to ${payment.address}"
                is SpliceCpfpOutgoingPayment -> "Accelerate transactions with CPFP"
                is InboundLiquidityOutgoingPayment -> "+${payment.lease.amount.sat} sat inbound liquidity"
            }
            row += ",${processField(details)}"
        }

        row += "\r\n"
        return row
    }

    private fun processField(str: String): String {
        return str.findAnyOf(listOf(",", "\"", "\n"))?.let {
            // - field must be enclosed in double-quotes
            // - a double-quote appearing inside the field must be
            //   escaped by preceding it with another double quote
            "\"${str.replace("\"", "\"\"")}\""
        } ?: str
    }
}