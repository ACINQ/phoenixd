package fr.acinq.phoenixd.json

import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentFailureDetailsTestsCommon {

    @Test
    fun `classifies outgoing payment failures`() {
        mapOf(
            "TrampolineFeeInsufficient" to "fee",
            "FeeInsufficient" to "fee",
            "TemporaryChannelFailure" to "liquidity",
            "UnknownNextPeer" to "remote",
            "PaymentTimeout" to "retry",
            "RetryExhausted" to "retry",
            "InsufficientBalance" to "local_balance",
            "NotEnoughFunds" to "local_balance",
            "SomethingUnexpected" to "unknown"
        ).forEach { (failure, category) ->
            assertEquals(category, paymentFailureCategory(failure), failure)
        }
    }
}
