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
    ByteVector32Serializer::class,
)

package fr.acinq.lightning.bin.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.bin.db.serializers.v1.ByteVector32Serializer
import fr.acinq.lightning.db.LightningOutgoingPayment
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


enum class LightningOutgoingPartStatusTypeVersion {
    SUCCEEDED_V0,
    /* Obsolete, do not use anymore. Failed parts are now typed, with a code and an option string message. */
    FAILED_V0,
    FAILED_V1,
}

sealed class LightningOutgoingPartStatusData {

    sealed class Succeeded : LightningOutgoingPartStatusData() {
        @Serializable
        data class V0(@Serializable val preimage: ByteVector32) : Succeeded()
    }

    sealed class Failed : LightningOutgoingPartStatusData() {
        @Serializable
        data class V0(val remoteFailureCode: Int?, val details: String) : Failed()

        @Serializable
        data class V1(val code: Int, val details: String?) : Failed()
    }

    companion object {
        fun deserialize(
            typeVersion: LightningOutgoingPartStatusTypeVersion,
            blob: ByteArray, completedAt: Long
        ): LightningOutgoingPayment.Part.Status = DbTypesHelper.decodeBlob(blob) { json, format ->
            when (typeVersion) {
                LightningOutgoingPartStatusTypeVersion.SUCCEEDED_V0 -> format.decodeFromString<Succeeded.V0>(json).let {
                    LightningOutgoingPayment.Part.Status.Succeeded(it.preimage, completedAt)
                }
                LightningOutgoingPartStatusTypeVersion.FAILED_V0 -> format.decodeFromString<Failed.V0>(json).let {
                    LightningOutgoingPayment.Part.Status.Failed(
                        failure = LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(message = it.details),
                        completedAt = completedAt,
                    )
                }
                LightningOutgoingPartStatusTypeVersion.FAILED_V1 -> format.decodeFromString<Failed.V1>(json).let {
                    LightningOutgoingPayment.Part.Status.Failed(
                        failure = when (it.code) {
                            0 -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(it.details ?: "n/a")
                            1 -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall
                            2 -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig
                            3 -> LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds
                            4 -> LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees
                            5 -> LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig
                            6 -> LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments
                            7 -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing
                            8 -> LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing
                            9 -> LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure
                            10 -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue
                            11 -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline
                            12 -> LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment
                            else -> LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable(it.details ?: "n/a")
                        },
                        completedAt = completedAt,
                    )
                }
            }
        }
    }
}

fun LightningOutgoingPayment.Part.Status.Succeeded.mapToDb() = LightningOutgoingPartStatusTypeVersion.SUCCEEDED_V0 to
        Json.encodeToString(LightningOutgoingPartStatusData.Succeeded.V0(preimage)).toByteArray(Charsets.UTF_8)

fun LightningOutgoingPayment.Part.Status.Failed.Failure.mapToDb(): Pair<LightningOutgoingPartStatusTypeVersion, ByteArray> {
    val (code, details) = when (this) {
        is LightningOutgoingPayment.Part.Status.Failed.Failure.Uninterpretable -> 0 to message
        is LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooSmall -> 1 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentAmountTooBig -> 2 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFunds -> 3 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.NotEnoughFees -> 4 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.PaymentExpiryTooBig -> 5 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments -> 6 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsSplicing -> 7 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.ChannelIsClosing -> 8 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure -> 9 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientLiquidityIssue -> 10 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientIsOffline -> 11 to null
        is LightningOutgoingPayment.Part.Status.Failed.Failure.RecipientRejectedPayment -> 12 to null
    }
    return LightningOutgoingPartStatusTypeVersion.FAILED_V1 to
            Json.encodeToString(LightningOutgoingPartStatusData.Failed.V1(code, details)).toByteArray(Charsets.UTF_8)
}