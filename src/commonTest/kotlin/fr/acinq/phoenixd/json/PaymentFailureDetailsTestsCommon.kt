package fr.acinq.phoenixd.json

import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.Bolt12InvoiceRequestFailure
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.payment.PaymentFailureCategory
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.OfferTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentFailureDetailsTestsCommon {

    @Test
    fun `uses lightning-kmp categories for outgoing payment failures`() {
        val paymentFailure = OutgoingPaymentFailure(
            reason = FinalFailure.RetryExhausted,
            failures = listOf(LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees))
        )

        val details = PaymentFailureDetails(paymentFailure)

        assertEquals("RetryExhausted", details.failure)
        assertEquals(PaymentFailureCategory.Fee, details.category)
        assertEquals(1, details.attemptCount)
        assertEquals("NotEnoughFees", details.failures.first().failure)
        assertEquals(PaymentFailureCategory.Fee, details.failures.first().category)
    }

    @Test
    fun `uses lightning-kmp categories for offer invoice request failures`() {
        val offer = OfferTypes.Offer.decode("lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqsespexwyy4tcadvgg89l9aljus6709kx235hhqrk6n8dey98uyuftzdqzrtkahuum7m56dxlnx8r6tffy54004l7kvs7pylmxx7xs4n54986qyqeeuhhunayntt50snmdkq4t7fzsgghpl69v9csgparek8kv7dlp5uqr8ymp5s4z9upmwr2s8xu020d45t5phqc8nljrq8gzsjmurzevawjz6j6rc95xwfvnhgfx6v4c3jha7jwynecrz3y092nn25ek4yl7xp9yu9ry9zqagt0ktn4wwvqg52v9ss9ls22sqyqqestzp2l6decpn87pq96udsvx").get()
        val request = OfferTypes.InvoiceRequest(offer, 1000.msat, 1, Features.empty, randomKey(), null, offer.chains.first())
        val details = PaymentFailureDetails(Bolt12InvoiceRequestFailure.NoResponse(request))

        assertEquals("NoResponse", details.failure)
        assertEquals(PaymentFailureCategory.Recipient, details.category)
        assertEquals(null, details.attemptCount)
        assertEquals(emptyList(), details.failures)
    }
}
