/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.phoenixd.db

import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.db.ChannelsDb
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SqliteChannelsDb(val driver: SqlDriver, database: PhoenixDatabase) : ChannelsDb {

    private val queries = database.channelsQueries

    override suspend fun addOrUpdateChannel(state: PersistedChannelState) {
        withContext(Dispatchers.Default) {
            queries.transaction {
                queries.getChannel(state.channelId).executeAsOneOrNull()?.run {
                    queries.updateChannel(channel_id = state.channelId, data_ = state)
                } ?: run {
                    queries.insertChannel(channel_id = state.channelId, data_ = state)
                }
            }
        }
    }

    override suspend fun removeChannel(channelId: ByteVector32) {
        withContext(Dispatchers.Default) {
            queries.deleteHtlcInfo(channel_id = channelId)
            queries.closeLocalChannel(channel_id = channelId)
        }
    }

    override suspend fun listLocalChannels(): List<PersistedChannelState> = withContext(Dispatchers.Default) {
        queries.listLocalChannels().executeAsList()
    }

    override suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry) {
        withContext(Dispatchers.Default) {
            queries.insertHtlcInfo(
                channel_id = channelId,
                commitment_number = commitmentNumber,
                payment_hash = paymentHash,
                cltv_expiry = cltvExpiry.toLong()
            )
        }
    }

    override suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>> {
        return withContext(Dispatchers.Default) {
            queries.listHtlcInfos(channel_id = channelId, commitment_number = commitmentNumber, mapper = { payment_hash, cltv_expiry ->
                payment_hash to CltvExpiry(cltv_expiry)
            }).executeAsList()
        }
    }

    override fun close() {
        driver.close()
    }
}