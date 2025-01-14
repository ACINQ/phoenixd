package fr.acinq.lightning.bin.db.payments

import io.ktor.http.*

data class PaymentMetadata(
    val externalId: String?,
    val webhookUrl: Url?,
    val createdAt: Long
)