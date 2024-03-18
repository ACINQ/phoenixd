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

@file:UseSerializers(
    SatoshiSerializer::class,
    ByteVector32Serializer::class,
)

package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.bin.db.serializers.v1.ByteVector32Serializer
import fr.acinq.lightning.bin.db.serializers.v1.SatoshiSerializer
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


enum class LightningOutgoingDetailsTypeVersion {
    NORMAL_V0,
    KEYSEND_V0,
    SWAPOUT_V0,
}

sealed class LightningOutgoingDetailsData {

    sealed class Normal : LightningOutgoingDetailsData() {
        @Serializable
        data class V0(val paymentRequest: String) : Normal()
    }

    sealed class KeySend : LightningOutgoingDetailsData() {
        @Serializable
        data class V0(@Serializable val preimage: ByteVector32) : KeySend()
    }

    sealed class SwapOut : LightningOutgoingDetailsData() {
        @Serializable
        data class V0(val address: String, val paymentRequest: String, @Serializable val swapOutFee: Satoshi) : SwapOut()
    }

    companion object {
        /** Deserialize the details of an outgoing payment. Return null if the details is for a legacy channel closing payment (see [deserializeLegacyClosingDetails]). */
        fun deserialize(typeVersion: LightningOutgoingDetailsTypeVersion, blob: ByteArray): LightningOutgoingPayment.Details = DbTypesHelper.decodeBlob(blob) { json, format ->
            when (typeVersion) {
                LightningOutgoingDetailsTypeVersion.NORMAL_V0 -> format.decodeFromString<Normal.V0>(json).let { LightningOutgoingPayment.Details.Normal(Bolt11Invoice.read(it.paymentRequest).get()) }
                LightningOutgoingDetailsTypeVersion.KEYSEND_V0 -> format.decodeFromString<KeySend.V0>(json).let { LightningOutgoingPayment.Details.KeySend(it.preimage) }
                LightningOutgoingDetailsTypeVersion.SWAPOUT_V0 -> format.decodeFromString<SwapOut.V0>(json).let { LightningOutgoingPayment.Details.SwapOut(it.address, Bolt11Invoice.read(it.paymentRequest).get(), it.swapOutFee) }
            }
        }
    }
}

fun LightningOutgoingPayment.Details.mapToDb(): Pair<LightningOutgoingDetailsTypeVersion, ByteArray> = when (this) {
    is LightningOutgoingPayment.Details.Normal -> LightningOutgoingDetailsTypeVersion.NORMAL_V0 to
            Json.encodeToString(LightningOutgoingDetailsData.Normal.V0(paymentRequest.write())).toByteArray(Charsets.UTF_8)
    is LightningOutgoingPayment.Details.KeySend -> LightningOutgoingDetailsTypeVersion.KEYSEND_V0 to
            Json.encodeToString(LightningOutgoingDetailsData.KeySend.V0(preimage)).toByteArray(Charsets.UTF_8)
    is LightningOutgoingPayment.Details.SwapOut -> LightningOutgoingDetailsTypeVersion.SWAPOUT_V0 to
            Json.encodeToString(LightningOutgoingDetailsData.SwapOut.V0(address, paymentRequest.write(), swapOutFee)).toByteArray(Charsets.UTF_8)
}
