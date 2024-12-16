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

package fr.acinq.lightning.bin.db.migrations.v4.types

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.bin.db.migrations.v3.json.ByteVector32Serializer
import fr.acinq.lightning.bin.db.migrations.v3.json.SatoshiSerializer
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.Bolt12Invoice
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json


enum class LightningOutgoingDetailsTypeVersion {
    NORMAL_V0,
    SWAPOUT_V0,
    BLINDED_V0
}

sealed class LightningOutgoingDetailsData {

    sealed class Normal : LightningOutgoingDetailsData() {
        @Serializable
        @SerialName("fr.acinq.lightning.bin.db.payments.LightningOutgoingDetailsData.V0")
        data class V0(val paymentRequest: String) : Normal()
    }

    sealed class SwapOut : LightningOutgoingDetailsData() {
        @Serializable
        @SerialName("fr.acinq.lightning.bin.db.payments.SwapOut.V0")
        data class V0(val address: String, val paymentRequest: String, val swapOutFee: Satoshi) : SwapOut()
    }

    sealed class Blinded : LightningOutgoingDetailsData() {
        @Serializable
        @SerialName("fr.acinq.lightning.bin.db.payments.Blinded.V0")
        data class V0(val paymentRequest: String, val payerKey: String) : Blinded()
    }

    companion object {
        /** Deserialize the details of an outgoing payment. Return null if the details is for a legacy channel closing payment (see [deserializeLegacyClosingDetails]). */
        fun deserialize(typeVersion: LightningOutgoingDetailsTypeVersion, blob: ByteArray): LightningOutgoingPayment.Details =
            when (typeVersion) {
                LightningOutgoingDetailsTypeVersion.NORMAL_V0 -> Json.decodeFromString<Normal.V0>(blob.decodeToString()).let {
                    LightningOutgoingPayment.Details.Normal(
                        paymentRequest = Bolt11Invoice.read(it.paymentRequest).get()
                    )
                }
                LightningOutgoingDetailsTypeVersion.SWAPOUT_V0 -> Json.decodeFromString<SwapOut.V0>(blob.decodeToString()).let {
                    @Suppress("DEPRECATION")
                    LightningOutgoingPayment.Details.SwapOut(
                        address = it.address,
                        paymentRequest = Bolt11Invoice.read(it.paymentRequest).get(),
                        swapOutFee = it.swapOutFee
                    )
                }
                LightningOutgoingDetailsTypeVersion.BLINDED_V0 -> Json.decodeFromString<Blinded.V0>(blob.decodeToString()).let {
                    LightningOutgoingPayment.Details.Blinded(
                        paymentRequest = Bolt12Invoice.fromString(it.paymentRequest).get(),
                        payerKey = PrivateKey.fromHex(it.payerKey),
                    )
                }
            }
    }
}
