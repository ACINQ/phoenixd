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

package fr.acinq.lightning.bin.db.payments

import fr.acinq.lightning.bin.db.WalletPaymentId
import fr.acinq.phoenix.db.PhoenixDatabase

class AggregatedQueries(val database: PhoenixDatabase) {
    private val queries = database.aggregatedQueriesQueries

    fun listOutgoingFromTo(from: Long, to: Long, limit: Long, offset: Long): List<WalletPaymentId> {
        return queries.listOutgoingWithin(startDate = from, endDate = to, limit = limit, offset = offset).executeAsList().mapNotNull {
            WalletPaymentId.create(it.type, it.id)
        }
    }

    fun listOutgoingSentFromTo(from: Long, to: Long, limit: Long, offset: Long): List<WalletPaymentId> {
        return queries.listSentWithin(startDate = from, endDate = to, limit = limit, offset = offset).executeAsList().mapNotNull {
            WalletPaymentId.create(it.type, it.id)
        }
    }
}