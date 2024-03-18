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
import fr.acinq.lightning.bin.db.payments.DbTypesHelper.decodeBlob
import fr.acinq.lightning.bin.db.serializers.v1.ByteVector32Serializer
import fr.acinq.lightning.bin.db.serializers.v1.SatoshiSerializer
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.FinalFailure
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LightningOutgoingStatusTypeVersion {
    SUCCEEDED_OFFCHAIN_V0,
    FAILED_V0,
}

sealed class LightningOutgoingStatusData {

    sealed class SucceededOffChain : LightningOutgoingStatusData() {
        @Serializable
        data class V0(@Serializable val preimage: ByteVector32) : SucceededOffChain()
    }

    sealed class Failed : LightningOutgoingStatusData() {
        @Serializable
        data class V0(val reason: String) : Failed()
    }

    companion object {

        fun deserialize(typeVersion: LightningOutgoingStatusTypeVersion, blob: ByteArray, completedAt: Long): LightningOutgoingPayment.Status = decodeBlob(blob) { json, format ->
            @Suppress("DEPRECATION")
            when (typeVersion) {
                LightningOutgoingStatusTypeVersion.SUCCEEDED_OFFCHAIN_V0 -> format.decodeFromString<SucceededOffChain.V0>(json).let {
                    LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(it.preimage, completedAt)
                }
                LightningOutgoingStatusTypeVersion.FAILED_V0 -> format.decodeFromString<Failed.V0>(json).let {
                    LightningOutgoingPayment.Status.Completed.Failed(deserializeFinalFailure(it.reason), completedAt)
                }
            }
        }

        internal fun serializeFinalFailure(failure: FinalFailure): String = failure::class.simpleName ?: "UnknownError"

        private fun deserializeFinalFailure(failure: String): FinalFailure = when (failure) {
            FinalFailure.InvalidPaymentAmount::class.simpleName -> FinalFailure.InvalidPaymentAmount
            FinalFailure.InvalidPaymentId::class.simpleName -> FinalFailure.InvalidPaymentId
            FinalFailure.NoAvailableChannels::class.simpleName -> FinalFailure.NoAvailableChannels
            FinalFailure.InsufficientBalance::class.simpleName -> FinalFailure.InsufficientBalance
            FinalFailure.NoRouteToRecipient::class.simpleName -> FinalFailure.NoRouteToRecipient
            FinalFailure.RecipientUnreachable::class.simpleName -> FinalFailure.RecipientUnreachable
            FinalFailure.RetryExhausted::class.simpleName -> FinalFailure.RetryExhausted
            FinalFailure.WalletRestarted::class.simpleName -> FinalFailure.WalletRestarted
            else -> FinalFailure.UnknownError
        }
    }
}

fun LightningOutgoingPayment.Status.Completed.mapToDb(): Pair<LightningOutgoingStatusTypeVersion, ByteArray> = when (this) {
    is LightningOutgoingPayment.Status.Completed.Succeeded.OffChain -> LightningOutgoingStatusTypeVersion.SUCCEEDED_OFFCHAIN_V0 to
            Json.encodeToString(LightningOutgoingStatusData.SucceededOffChain.V0(preimage)).toByteArray(Charsets.UTF_8)
    is LightningOutgoingPayment.Status.Completed.Failed -> LightningOutgoingStatusTypeVersion.FAILED_V0 to
            Json.encodeToString(LightningOutgoingStatusData.Failed.V0(LightningOutgoingStatusData.serializeFinalFailure(reason))).toByteArray(Charsets.UTF_8)
}
