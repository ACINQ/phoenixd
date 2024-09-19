package fr.acinq.lightning.bin.payments

import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.phoenix.types.db.LiquidityAds
import fr.acinq.phoenix.types.db.LiquidityAds.PaymentDetails.Companion.asDb
import fr.acinq.phoenix.types.db.LiquidityAds.PaymentDetails.Companion.asOfficial
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class SerializationTest {

    @Test
    fun `polymorphic serialization`() {

        val paymentDetails = fr.acinq.lightning.wire.LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32(), randomBytes32()))

        val paymentDetailsV0: LiquidityAds.PaymentDetails = paymentDetails.asDb()

        val serialized = Json.encodeToString(paymentDetailsV0)
        println(serialized)

        val deserialized = Json.decodeFromString<LiquidityAds.PaymentDetails>(serialized)

        println(deserialized)

        val paymentDetails_ = deserialized.asOfficial()
    }
}