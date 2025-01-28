@file:Suppress("DEPRECATION")

package fr.acinq.lightning.bin.csv

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.csv.WalletPaymentCsvWriter.Type
import fr.acinq.lightning.db.*
import fr.acinq.lightning.utils.*
import kotlinx.datetime.Instant
import okio.Path

/**
 * Exports a payments db items to a csv file.
 *
 * The three main columns are:
 * - `type`: can be any of [Type].
 * - `amount_msat`: positive or negative, summing this value over all rows results in the current balance.
 * - `fee_credit_msat`: positive or negative, summing this value over all rows results in the current fee credit.
 *
 * Other columns are metadata (timestamp, payment hash, txid, fee details).
 */
class WalletPaymentCsvWriter(path: Path) : CsvWriter(path) {

    private val FIELD_DATE = "date"
    private val FIELD_ID = "id"
    private val FIELD_TYPE = "type"
    private val FIELD_AMOUNT_MSAT = "amount_msat"
    private val FIELD_FEE_CREDIT_MSAT = "fee_credit_msat"
    private val FIELD_MINING_FEE_SAT = "mining_fee_sat"
    private val FIELD_SERVICE_FEE_MSAT = "service_fee_msat"
    private val FIELD_PAYMENT_HASH = "payment_hash"
    private val FIELD_TX_ID = "tx_id"

    init {
        addRow(FIELD_DATE, FIELD_ID, FIELD_TYPE, FIELD_AMOUNT_MSAT, FIELD_FEE_CREDIT_MSAT, FIELD_MINING_FEE_SAT, FIELD_SERVICE_FEE_MSAT, FIELD_PAYMENT_HASH, FIELD_TX_ID)
    }

    @Suppress("EnumEntryName")
    enum class Type {
        legacy_swap_in,
        legacy_swap_out,
        legacy_pay_to_open,
        swap_in,
        swap_out,
        fee_bumping,
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
        id: UUID,
        details: Details
    ) {
        val dateStr = Instant.fromEpochMilliseconds(timestamp).toString() // ISO-8601 format
        addRow(
            dateStr,
            id.toString(),
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
        val id = payment.id

        val details: Details? = when (payment) {
            is LightningIncomingPayment -> Details(
                type = Type.lightning_received,
                amount = payment.amount,
                feeCredit = payment.parts.filterIsInstance<LightningIncomingPayment.Part.FeeCredit>().map { it.amountReceived }.sum() - (payment.liquidityPurchaseDetails?.feeCreditUsed ?: 0.msat),
                miningFee = payment.liquidityPurchaseDetails?.miningFee ?: 0.sat,
                serviceFee = payment.liquidityPurchaseDetails?.purchase?.fees?.serviceFee?.toMilliSatoshi() ?: 0.msat,
                paymentHash = payment.paymentHash,
                txId = payment.liquidityPurchaseDetails?.txId
            )
            is LegacySwapInIncomingPayment -> Details(
                Type.legacy_swap_in,
                amount = payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.fees.truncateToSatoshi(),
                serviceFee = 0.msat,
                paymentHash = null,
                txId = null
            )
            is LegacyPayToOpenIncomingPayment -> Details(
                type = Type.legacy_pay_to_open,
                amount = payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.parts.filterIsInstance<LegacyPayToOpenIncomingPayment.Part.OnChain>().map { it.miningFee }.sum(),
                serviceFee = payment.parts.filterIsInstance<LegacyPayToOpenIncomingPayment.Part.OnChain>().map { it.serviceFee }.sum(),
                paymentHash = payment.paymentHash,
                txId = payment.parts.filterIsInstance<LegacyPayToOpenIncomingPayment.Part.OnChain>().map { it.txId }.firstOrNull()
            )
            is OnChainIncomingPayment -> Details(
                Type.swap_in,
                amount = payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.miningFee,
                serviceFee = payment.serviceFee,
                paymentHash = null,
                txId = payment.txId
            )
            is LightningOutgoingPayment -> when (val details = payment.details) {
                is LightningOutgoingPayment.Details.Normal -> Details(
                    Type.lightning_sent,
                    amount = -payment.amount,
                    feeCredit = 0.msat,
                    miningFee = 0.sat,
                    serviceFee = payment.fees,
                    paymentHash = payment.paymentHash,
                    txId = null
                )
                is LightningOutgoingPayment.Details.SwapOut -> Details(
                    Type.legacy_swap_out,
                    amount = -payment.amount,
                    feeCredit = 0.msat,
                    miningFee = details.swapOutFee,
                    serviceFee = 0.msat,
                    paymentHash = null,
                    txId = null
                )
                is LightningOutgoingPayment.Details.Blinded -> Details(
                    Type.lightning_sent,
                    amount = -payment.amount,
                    feeCredit = 0.msat,
                    miningFee = 0.sat,
                    serviceFee = payment.fees,
                    paymentHash = payment.paymentHash,
                    txId = null
                )
            }
            is SpliceOutgoingPayment -> Details(
                Type.swap_out,
                amount = -payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.miningFee,
                serviceFee = 0.msat,
                paymentHash = null,
                txId = payment.txId
            )
            is ChannelCloseOutgoingPayment -> Details(
                Type.channel_close,
                amount = -payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.miningFee,
                serviceFee = 0.msat,
                paymentHash = null,
                txId = payment.txId
            )
            is SpliceCpfpOutgoingPayment -> Details(
                Type.fee_bumping,
                amount = -payment.amount,
                feeCredit = 0.msat,
                miningFee = payment.miningFee,
                serviceFee = 0.msat,
                paymentHash = null,
                txId = payment.txId
            )
            is AutomaticLiquidityPurchasePayment -> if (payment.incomingPaymentReceivedAt == null) {
                Details(
                    Type.liquidity_purchase,
                    amount = -payment.amount,
                    feeCredit = -payment.liquidityPurchaseDetails.feeCreditUsed,
                    miningFee = payment.miningFee,
                    serviceFee = payment.serviceFee,
                    paymentHash = null,
                    txId = payment.txId
                )
            } else {
                // If the corresponding Lightning payment was received, then liquidity fees will be included in the Lightning payment
                null
            }
            is ManualLiquidityPurchasePayment -> Details(
                Type.liquidity_purchase,
                amount = -payment.amount,
                feeCredit = -payment.liquidityPurchaseDetails.feeCreditUsed,
                miningFee = payment.miningFee,
                serviceFee = payment.serviceFee,
                paymentHash = null,
                txId = payment.txId
            )
        }

        details?.let { addRow(timestamp, id, it) }

    }

}