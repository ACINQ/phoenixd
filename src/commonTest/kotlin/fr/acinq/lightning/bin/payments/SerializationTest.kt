package fr.acinq.lightning.bin.payments

import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.types.db.LiquidityAdsDb
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class SerializationTest {

    @Test
    fun `polymorphic serialization`() {

        val paymentDetails = LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32(), randomBytes32()))

        val paymentDetailsV0: LiquidityAdsDb.PaymentDetailsDb = LiquidityAdsDb.PaymentDetailsDb.FutureHtlc.V0(paymentDetails.paymentHashes)

        val serialized = Json.encodeToString(paymentDetailsV0)
        println(serialized)

        val deserialized = Json.decodeFromString<LiquidityAdsDb.PaymentDetailsDb>(serialized)

        println(deserialized)

        val paymentDetails_ = LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32(), randomBytes32()))
    }
}