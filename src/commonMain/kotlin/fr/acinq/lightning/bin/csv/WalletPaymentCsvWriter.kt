package fr.acinq.lightning.bin.csv

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toMilliSatoshi
import kotlinx.datetime.Instant
import okio.Path

/**
 * Exports a payments db items to a csv file.
 *
 * The three main columns are:
 * - `type`: can be any of [Type].
 * - `amount_msat`: positive or negative, will be non-zero for all types except [Type.fee_credit]. Summing this value over all rows results in the current balance.
 * - `fee_credit_msat`: positive or negative, will be zero for all types except [Type.fee_credit]. Summing this value over all rows results in the current fee credit.
 *
 * Other columns are metadata (timestamp, payment hash, txid, fee details).
 */
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

    @Suppress("EnumEntryName")
    enum class Type {
        legacy_swap_in,
        legacy_swap_out,
        legacy_pay_to_open,
        legacy_pay_to_splice,
        swap_in,
        swap_out,
        fee_bumping,
        fee_credit,
        lightning_received,
        lightning_sent,
        liquidity_purchase,
        channel_close,
    }

    data class Details(
        val type: Type,
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
        addRow(
            dateStr,
            details.type.toString(),
            details.amount.msat.toString(),
            details.feeCredit.msat.toString(),
            details.miningFee.sat.toString(),
            details.serviceFee.msat.toString(),
            details.paymentHash?.toHex() ?: "",
            details.txId?.toString() ?: ""
        )
    }

    fun add(payment: WalletPayment) {
        val timestamp = payment.completedAt ?: payment.createdAt

        val details: List<Details> = when (payment) {
            is IncomingPayment -> when (val origin = payment.origin) {
                is IncomingPayment.Origin.Invoice -> extractLightningPaymentParts(payment)
                is IncomingPayment.Origin.SwapIn -> listOf(
                    Details(
                        type = Type.legacy_swap_in,
                        amount = payment.amount,
                        feeCredit = 0.msat,
                        miningFee = payment.fees.truncateToSatoshi(),
                        serviceFee = 0.msat,
                        paymentHash = payment.paymentHash,
                        txId = null
                    )
                )
                is IncomingPayment.Origin.OnChain -> listOf(Details(Type.swap_in, amount = payment.amount, feeCredit = 0.msat, miningFee = payment.fees.truncateToSatoshi(), serviceFee = 0.msat, paymentHash = null, txId = origin.txId))
                is IncomingPayment.Origin.Offer -> extractLightningPaymentParts(payment)
            }

            is LightningOutgoingPayment -> when (val details = payment.details) {
                is LightningOutgoingPayment.Details.Normal -> listOf(Details(Type.lightning_sent, amount = -payment.amount, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null))
                is LightningOutgoingPayment.Details.SwapOut -> listOf(Details(Type.legacy_swap_out, amount = -payment.amount, feeCredit = 0.msat, miningFee = details.swapOutFee, serviceFee = 0.msat, paymentHash = null, txId = null))
                is LightningOutgoingPayment.Details.Blinded -> listOf(Details(Type.lightning_sent, amount = -payment.amount, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = payment.fees, paymentHash = payment.paymentHash, txId = null))
            }

            is SpliceOutgoingPayment -> listOf(Details(Type.swap_out, amount = -payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is ChannelCloseOutgoingPayment -> listOf(Details(Type.channel_close, amount = -payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is SpliceCpfpOutgoingPayment -> listOf(Details(Type.fee_bumping, amount = -payment.amount, feeCredit = 0.msat, miningFee = payment.miningFees, serviceFee = 0.msat, paymentHash = null, txId = payment.txId))
            is InboundLiquidityOutgoingPayment -> listOf(
                Details(
                    Type.liquidity_purchase,
                    amount = -payment.feePaidFromChannelBalance.total.toMilliSatoshi(),
                    feeCredit = -payment.feeCreditUsed,
                    miningFee = payment.miningFees,
                    serviceFee = payment.serviceFees.toMilliSatoshi(),
                    paymentHash = null,
                    txId = payment.txId
                )
            )
        }

        details.forEach { addRow(timestamp, it) }

    }

    private fun extractLightningPaymentParts(payment: IncomingPayment): List<Details> = payment.received?.receivedWith.orEmpty()
        .map {
            when (it) {
                is IncomingPayment.ReceivedWith.LightningPayment -> Details(Type.lightning_received, amount = it.amountReceived, feeCredit = 0.msat, miningFee = 0.sat, serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.ReceivedWith.AddedToFeeCredit -> Details(Type.fee_credit, amount = 0.msat, feeCredit = it.amountReceived, miningFee = 0.sat, serviceFee = 0.msat, paymentHash = payment.paymentHash, txId = null)
                is IncomingPayment.ReceivedWith.NewChannel -> Details(Type.legacy_pay_to_open, amount = it.amountReceived, feeCredit = 0.msat, miningFee = it.miningFee, serviceFee = it.serviceFee, paymentHash = payment.paymentHash, txId = it.txId)
                is IncomingPayment.ReceivedWith.SpliceIn -> Details(Type.legacy_pay_to_splice, amount = it.amountReceived, feeCredit = 0.msat, miningFee = it.miningFee, serviceFee = it.serviceFee, paymentHash = payment.paymentHash, txId = it.txId)
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