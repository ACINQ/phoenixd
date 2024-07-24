package fr.acinq.lightning.bin.db

import io.ktor.http.*

/**
 * Metadata for a given payment, incoming or outgoing.
 *
 * @param externalId A custom identifier used in an external system and attached to a payment at creation.
 *              Useful to track a payment from the outside.
 * @param webhookUrl A webhook url that will be notified for that specific payment.
 * @param createdAt Timestamp in millis when the metadata was created.
 */
data class PaymentMetadata(
    val externalId: String?,
    val webhookUrl: Url?,
    val createdAt: Long
)