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

package fr.acinq.lightning.bin.db.payments

import fr.acinq.lightning.db.ChannelCloseOutgoingPayment
import fr.acinq.lightning.db.ChannelClosingType
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


enum class ClosingInfoTypeVersion {
    // basic type, containing only a [ChannelClosingType] field
    CLOSING_INFO_V0,
}

sealed class ClosingInfoData {

    @Serializable
    data class V0(val closingType: ChannelClosingType)

    companion object {
        fun deserialize(typeVersion: ClosingInfoTypeVersion, blob: ByteArray): ChannelClosingType = DbTypesHelper.decodeBlob(blob) { json, format ->
            when (typeVersion) {
                ClosingInfoTypeVersion.CLOSING_INFO_V0 -> format.decodeFromString<V0>(json).closingType
            }
        }
    }
}

fun ChannelCloseOutgoingPayment.mapClosingTypeToDb() = ClosingInfoTypeVersion.CLOSING_INFO_V0 to
        Json.encodeToString(ClosingInfoData.V0(this.closingType)).toByteArray(Charsets.UTF_8)
