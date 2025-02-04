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

package fr.acinq.phoenixd.db.migrations.v4.types

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.phoenixd.db.migrations.v3.json.ByteVector32Serializer
import fr.acinq.phoenixd.db.migrations.v3.json.SatoshiSerializer
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.payment.FinalFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json

enum class LightningOutgoingStatusTypeVersion {
    SUCCEEDED_OFFCHAIN_V0,
    FAILED_V0,
}

sealed class LightningOutgoingStatusData {

    sealed class SucceededOffChain : LightningOutgoingStatusData() {
        @Serializable
        @SerialName("fr.acinq.lightning.bin.db.payments.LightningOutgoingStatusData.SucceededOffChain.V0")
        data class V0(@Serializable val preimage: ByteVector32) : SucceededOffChain()
    }

    sealed class Failed : LightningOutgoingStatusData() {
        @Serializable
        @SerialName("fr.acinq.lightning.bin.db.payments.LightningOutgoingStatusData.Failed.V0")
        data class V0(val reason: String) : Failed()
    }

    companion object {

        fun deserialize(typeVersion: LightningOutgoingStatusTypeVersion, blob: ByteArray, completedAt: Long): LightningOutgoingPayment.Status =
            when (typeVersion) {
                LightningOutgoingStatusTypeVersion.SUCCEEDED_OFFCHAIN_V0 -> Json.decodeFromString<SucceededOffChain.V0>(blob.decodeToString()).let {
                    LightningOutgoingPayment.Status.Succeeded(it.preimage, completedAt)
                }
                LightningOutgoingStatusTypeVersion.FAILED_V0 -> Json.decodeFromString<Failed.V0>(blob.decodeToString()).let {
                    LightningOutgoingPayment.Status.Failed(deserializeFinalFailure(it.reason), completedAt)
                }
            }

        private fun deserializeFinalFailure(failure: String): FinalFailure = when (failure) {
            FinalFailure.InvalidPaymentAmount::class.simpleName -> FinalFailure.InvalidPaymentAmount
            FinalFailure.InvalidPaymentId::class.simpleName -> FinalFailure.InvalidPaymentId
            FinalFailure.NoAvailableChannels::class.simpleName -> FinalFailure.NoAvailableChannels
            FinalFailure.InsufficientBalance::class.simpleName -> FinalFailure.InsufficientBalance
            FinalFailure.RecipientUnreachable::class.simpleName -> FinalFailure.RecipientUnreachable
            FinalFailure.RetryExhausted::class.simpleName -> FinalFailure.RetryExhausted
            FinalFailure.WalletRestarted::class.simpleName -> FinalFailure.WalletRestarted
            FinalFailure.AlreadyPaid::class.simpleName -> FinalFailure.AlreadyPaid
            FinalFailure.ChannelClosing::class.simpleName -> FinalFailure.ChannelClosing
            FinalFailure.ChannelOpening::class.simpleName -> FinalFailure.ChannelOpening
            FinalFailure.ChannelNotConnected::class.simpleName -> FinalFailure.ChannelNotConnected
            FinalFailure.FeaturesNotSupported::class.simpleName -> FinalFailure.FeaturesNotSupported
            else -> FinalFailure.UnknownError
        }
    }
}
