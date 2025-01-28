/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.lightning.bin.db.migrations.v4.queries

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.bin.db.migrations.v4.types.*
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.LightningOutgoingPayment.Part.HopDesc
import fr.acinq.lightning.utils.UUID
import fr.acinq.secp256k1.Hex

object LightningOutgoingQueries {

    @Suppress("UNUSED_PARAMETER")
    private fun mapLightningOutgoingPaymentWithoutParts(
        id: String,
        recipient_amount_msat: Long,
        recipient_node_id: String,
        payment_hash: ByteArray,
        details_type: LightningOutgoingDetailsTypeVersion,
        details_blob: ByteArray,
        created_at: Long,
        completed_at: Long?,
        status_type: LightningOutgoingStatusTypeVersion?,
        status_blob: ByteArray?
    ): LightningOutgoingPayment {
        val details = LightningOutgoingDetailsData.deserialize(details_type, details_blob)
        return LightningOutgoingPayment(
            id = UUID.fromString(id),
            recipientAmount = MilliSatoshi(recipient_amount_msat),
            recipient = PublicKey.parse(Hex.decode(recipient_node_id)),
            details = details,
            parts = listOf(),
            status = mapPaymentStatus(status_type, status_blob, completed_at),
            createdAt = created_at
        )
    }

    fun mapLightningOutgoingPayment(
        id: String,
        recipient_amount_msat: Long,
        recipient_node_id: String,
        payment_hash: ByteArray,
        details_type: LightningOutgoingDetailsTypeVersion,
        details_blob: ByteArray,
        created_at: Long,
        completed_at: Long?,
        status_type: LightningOutgoingStatusTypeVersion?,
        status_blob: ByteArray?,
        // lightning parts data, may be null
        lightning_part_id: String?,
        lightning_part_amount_msat: Long?,
        lightning_part_route: List<HopDesc>?,
        lightning_part_created_at: Long?,
        lightning_part_completed_at: Long?,
        lightning_part_status_type: LightningOutgoingPartStatusTypeVersion?,
        lightning_part_status_blob: ByteArray?,
    ): LightningOutgoingPayment {

        val parts = if (lightning_part_id != null && lightning_part_amount_msat != null && lightning_part_route != null && lightning_part_created_at != null) {
            listOf(
                mapLightningPart(
                    id = lightning_part_id,
                    amountMsat = lightning_part_amount_msat,
                    route = lightning_part_route,
                    createdAt = lightning_part_created_at,
                    completedAt = lightning_part_completed_at,
                    statusType = lightning_part_status_type,
                    statusBlob = lightning_part_status_blob
                )
            )
        } else emptyList()

        return mapLightningOutgoingPaymentWithoutParts(
            id = id,
            recipient_amount_msat = recipient_amount_msat,
            recipient_node_id = recipient_node_id,
            payment_hash = payment_hash,
            details_type = details_type,
            details_blob = details_blob,
            created_at = created_at,
            completed_at = completed_at,
            status_type = status_type,
            status_blob = status_blob
        ).copy(
            parts = parts
        )
    }

    private fun mapLightningPart(
        id: String,
        amountMsat: Long,
        route: List<HopDesc>,
        createdAt: Long,
        completedAt: Long?,
        statusType: LightningOutgoingPartStatusTypeVersion?,
        statusBlob: ByteArray?
    ): LightningOutgoingPayment.Part {
        return LightningOutgoingPayment.Part(
            id = UUID.fromString(id),
            amount = MilliSatoshi(amountMsat),
            route = route,
            status = mapLightningPartStatus(
                statusType = statusType,
                statusBlob = statusBlob,
                completedAt = completedAt
            ),
            createdAt = createdAt
        )
    }

    private fun mapPaymentStatus(
        statusType: LightningOutgoingStatusTypeVersion?,
        statusBlob: ByteArray?,
        completedAt: Long?,
    ): LightningOutgoingPayment.Status = when {
        completedAt == null && statusType == null && statusBlob == null -> LightningOutgoingPayment.Status.Pending
        completedAt != null && statusType != null && statusBlob != null -> LightningOutgoingStatusData.deserialize(statusType, statusBlob, completedAt)
        else -> throw UnhandledOutgoingStatus(completedAt, statusType, statusBlob)
    }

    private fun mapLightningPartStatus(
        statusType: LightningOutgoingPartStatusTypeVersion?,
        statusBlob: ByteArray?,
        completedAt: Long?,
    ): LightningOutgoingPayment.Part.Status = when {
        completedAt == null && statusType == null && statusBlob == null -> LightningOutgoingPayment.Part.Status.Pending
        completedAt != null && statusType != null && statusBlob != null -> LightningOutgoingPartStatusData.deserialize(statusType, statusBlob, completedAt)
        else -> throw UnhandledOutgoingPartStatus(statusType, statusBlob, completedAt)
    }

    val hopDescAdapter: ColumnAdapter<List<HopDesc>, String> = object : ColumnAdapter<List<HopDesc>, String> {
        override fun decode(databaseValue: String): List<HopDesc> = when {
            databaseValue.isEmpty() -> listOf()
            else -> databaseValue.split(";").map { hop ->
                val els = hop.split(":")
                val n1 = PublicKey.parse(Hex.decode(els[0]))
                val n2 = PublicKey.parse(Hex.decode(els[1]))
                val cid = els[2].takeIf { it.isNotBlank() }?.run { ShortChannelId(this) }
                HopDesc(n1, n2, cid)
            }
        }

        override fun encode(value: List<HopDesc>): String = value.joinToString(";") {
            "${it.nodeId}:${it.nextNodeId}:${it.shortChannelId ?: ""}"
        }
    }
}

data class UnhandledOutgoingStatus(val completedAt: Long?, val statusTypeVersion: LightningOutgoingStatusTypeVersion?, val statusData: ByteArray?) :
    RuntimeException("cannot map outgoing payment status data with completed_at=$completedAt status_type=$statusTypeVersion status=$statusData")

data class UnhandledOutgoingPartStatus(val status_type: LightningOutgoingPartStatusTypeVersion?, val status_blob: ByteArray?, val completedAt: Long?) :
    RuntimeException("cannot map outgoing part status data [ completed_at=$completedAt status_type=$status_type status_blob=$status_blob]")