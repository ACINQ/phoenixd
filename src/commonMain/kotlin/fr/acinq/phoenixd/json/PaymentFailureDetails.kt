package fr.acinq.phoenixd.json

import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.Bolt12InvoiceRequestFailure
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.payment.PaymentFailureCategory
import kotlinx.serialization.Serializable

internal fun paymentFailureName(failure: Any): String = failure::class.simpleName ?: failure.toString().substringBefore("(").substringAfterLast(".")

private fun paymentFailureDetails(failure: LightningOutgoingPayment.Part.Status.Failed.Failure): String? {
    val details = failure.toString()
    return details.takeIf { it != paymentFailureName(failure) }
}

@Serializable
data class PaymentFailureDetails(
    val failure: String,
    val category: PaymentFailureCategory,
    val attemptCount: Int? = null,
    val failures: List<PaymentFailureAttempt> = emptyList(),
    val details: String? = null
) {
    constructor(failure: OutgoingPaymentFailure) : this(
        failure = paymentFailureName(failure.reason),
        category = failure.category,
        attemptCount = failure.failures.size,
        failures = failure.failures.map { PaymentFailureAttempt(it) }
    )

    constructor(failure: Bolt12InvoiceRequestFailure) : this(
        failure = paymentFailureName(failure),
        category = failure.category
    )
}

@Serializable
data class PaymentFailureAttempt(
    val failure: String,
    val category: PaymentFailureCategory,
    val details: String?
) {
    constructor(failure: LightningOutgoingPayment.Part.Status.Failed) : this(
        failure = paymentFailureName(failure.failure),
        category = failure.failure.category,
        details = paymentFailureDetails(failure.failure)
    )
}
