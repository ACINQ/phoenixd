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

package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.bin.db.payments.*
import fr.acinq.lightning.bin.db.payments.LinkTxToPaymentQueries
import fr.acinq.lightning.bin.db.payments.PaymentsMetadataQueries
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.FailureMessage
import fr.acinq.phoenix.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlitePaymentsDb(val database: PhoenixDatabase) : PaymentsDb {

    private val inQueries = IncomingQueries(database)
    private val lightningOutgoingQueries = LightningOutgoingQueries(database)
    private val spliceOutQueries = SpliceOutgoingQueries(database)
    private val channelCloseQueries = ChannelCloseOutgoingQueries(database)
    private val cpfpQueries = SpliceCpfpOutgoingQueries(database)
    private val linkTxToPaymentQueries = LinkTxToPaymentQueries(database)
    private val inboundLiquidityQueries = InboundLiquidityQueries(database)
    private val aggregatedQueries = AggregatedQueries(database)
    val metadataQueries = PaymentsMetadataQueries(database)

    override suspend fun addOutgoingLightningParts(
        parentId: UUID,
        parts: List<LightningOutgoingPayment.Part>
    ) {
        withContext(Dispatchers.Default) {
            lightningOutgoingQueries.addLightningParts(parentId, parts)
        }
    }

    override suspend fun addOutgoingPayment(
        outgoingPayment: OutgoingPayment
    ) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (outgoingPayment) {
                    is LightningOutgoingPayment -> {
                        lightningOutgoingQueries.addLightningOutgoingPayment(outgoingPayment)
                    }
                    is SpliceOutgoingPayment -> {
                        spliceOutQueries.addSpliceOutgoingPayment(outgoingPayment)
                        linkTxToPaymentQueries.linkTxToPayment(
                            txId = outgoingPayment.txId,
                            walletPaymentId = outgoingPayment.walletPaymentId()
                        )
                    }
                    is ChannelCloseOutgoingPayment -> {
                        channelCloseQueries.addChannelCloseOutgoingPayment(outgoingPayment)
                        linkTxToPaymentQueries.linkTxToPayment(
                            txId = outgoingPayment.txId,
                            walletPaymentId = outgoingPayment.walletPaymentId()
                        )
                    }
                    is SpliceCpfpOutgoingPayment -> {
                        cpfpQueries.addCpfpPayment(outgoingPayment)
                        linkTxToPaymentQueries.linkTxToPayment(outgoingPayment.txId, outgoingPayment.walletPaymentId())
                    }
                    is InboundLiquidityOutgoingPayment -> {
                        inboundLiquidityQueries.add(outgoingPayment)
                        linkTxToPaymentQueries.linkTxToPayment(outgoingPayment.txId, outgoingPayment.walletPaymentId())
                    }
                }
            }
        }
    }

    override suspend fun completeOutgoingPaymentOffchain(
        id: UUID,
        preimage: ByteVector32,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            lightningOutgoingQueries.completePayment(id, LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, completedAt))
        }
    }

    override suspend fun completeOutgoingPaymentOffchain(
        id: UUID,
        finalFailure: FinalFailure,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            lightningOutgoingQueries.completePayment(id, LightningOutgoingPayment.Status.Completed.Failed(finalFailure, completedAt))
        }
    }

    override suspend fun completeOutgoingLightningPart(
        partId: UUID,
        preimage: ByteVector32,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            lightningOutgoingQueries.updateLightningPart(partId, preimage, completedAt)
        }
    }

    override suspend fun completeOutgoingLightningPart(
        partId: UUID,
        failure: Either<ChannelException, FailureMessage>,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            lightningOutgoingQueries.updateLightningPart(partId, failure, completedAt)
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        lightningOutgoingQueries.getPayment(id)
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        lightningOutgoingQueries.getPaymentFromPartId(partId)
    }

    override suspend fun listLightningOutgoingPayments(
        paymentHash: ByteVector32
    ): List<LightningOutgoingPayment> = withContext(Dispatchers.Default) {
        lightningOutgoingQueries.listLightningOutgoingPayments(paymentHash)
    }

    // ---- incoming payments

    override suspend fun addIncomingPayment(
        preimage: ByteVector32,
        origin: IncomingPayment.Origin,
        createdAt: Long
    ): IncomingPayment {
        val paymentHash = Crypto.sha256(preimage).toByteVector32()

        return withContext(Dispatchers.Default) {
            database.transactionWithResult {
                inQueries.addIncomingPayment(preimage, paymentHash, origin, createdAt)
                inQueries.getIncomingPayment(paymentHash)!!
            }
        }
    }

    override suspend fun receivePayment(
        paymentHash: ByteVector32,
        receivedWith: List<IncomingPayment.ReceivedWith>,
        receivedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            database.transaction {
                inQueries.receivePayment(paymentHash, receivedWith, receivedAt)
                // if one received-with is on-chain, save the tx id to the db
                receivedWith.filterIsInstance<IncomingPayment.ReceivedWith.OnChainIncomingPayment>().firstOrNull()?.let {
                    linkTxToPaymentQueries.linkTxToPayment(it.txId, WalletPaymentId.IncomingPaymentId(paymentHash))
                }
            }
        }
    }

    override suspend fun setLocked(txId: TxId) {
        database.transaction {
            val lockedAt = currentTimestampMillis()
            linkTxToPaymentQueries.setLocked(txId, lockedAt)
            linkTxToPaymentQueries.listWalletPaymentIdsForTx(txId).forEach { walletPaymentId ->
                when (walletPaymentId) {
                    is WalletPaymentId.IncomingPaymentId -> {
                        inQueries.setLocked(walletPaymentId.paymentHash, lockedAt)
                    }
                    is WalletPaymentId.LightningOutgoingPaymentId -> {
                        // LN payments need not be locked
                    }
                    is WalletPaymentId.SpliceOutgoingPaymentId -> {
                        spliceOutQueries.setLocked(walletPaymentId.id, lockedAt)
                    }
                    is WalletPaymentId.ChannelCloseOutgoingPaymentId -> {
                        channelCloseQueries.setLocked(walletPaymentId.id, lockedAt)
                    }
                    is WalletPaymentId.SpliceCpfpOutgoingPaymentId -> {
                        cpfpQueries.setLocked(walletPaymentId.id, lockedAt)
                    }
                    is WalletPaymentId.InboundLiquidityOutgoingPaymentId -> {
                        inboundLiquidityQueries.setLocked(walletPaymentId.id, lockedAt)
                    }
                }
            }
        }
    }

    suspend fun setConfirmed(txId: TxId) = withContext(Dispatchers.Default) {
        database.transaction {
            val confirmedAt = currentTimestampMillis()
            linkTxToPaymentQueries.setConfirmed(txId, confirmedAt)
            linkTxToPaymentQueries.listWalletPaymentIdsForTx(txId).forEach { walletPaymentId ->
                when (walletPaymentId) {
                    is WalletPaymentId.IncomingPaymentId -> {
                        inQueries.setConfirmed(walletPaymentId.paymentHash, confirmedAt)
                    }
                    is WalletPaymentId.LightningOutgoingPaymentId -> {
                        // LN payments need not be confirmed
                    }
                    is WalletPaymentId.SpliceOutgoingPaymentId -> {
                        spliceOutQueries.setConfirmed(walletPaymentId.id, confirmedAt)
                    }
                    is WalletPaymentId.ChannelCloseOutgoingPaymentId -> {
                        channelCloseQueries.setConfirmed(walletPaymentId.id, confirmedAt)
                    }
                    is WalletPaymentId.SpliceCpfpOutgoingPaymentId -> {
                        cpfpQueries.setConfirmed(walletPaymentId.id, confirmedAt)
                    }
                    is WalletPaymentId.InboundLiquidityOutgoingPaymentId -> {
                        inboundLiquidityQueries.setConfirmed(walletPaymentId.id, confirmedAt)
                    }
                }
            }
        }
    }

    override suspend fun getIncomingPayment(
        paymentHash: ByteVector32
    ): IncomingPayment? = withContext(Dispatchers.Default) {
        inQueries.getIncomingPayment(paymentHash)
    }

    override suspend fun listExpiredPayments(
        fromCreatedAt: Long,
        toCreatedAt: Long
    ): List<IncomingPayment> = withContext(Dispatchers.Default) {
        inQueries.listExpiredPayments(fromCreatedAt, toCreatedAt)
    }

    override suspend fun removeIncomingPayment(
        paymentHash: ByteVector32
    ): Boolean = withContext(Dispatchers.Default) {
        inQueries.deleteIncomingPayment(paymentHash)
    }

    // ---- list payments with filter

    suspend fun listReceivedFromTo(from: Long, to: Long): List<Pair<IncomingPayment, PaymentMetadata?>> {
        return withContext(Dispatchers.Default) {
            inQueries.listReceivedFromTo(from, to).map { payment ->
                val metadata = metadataQueries.get(payment.walletPaymentId())
                payment to metadata
            }
        }
    }

    suspend fun listOutgoingFromTo(from: Long, to: Long, limit: Long, offset: Long): List<Pair<OutgoingPayment, PaymentMetadata?>> {
        return withContext(Dispatchers.Default) {
            aggregatedQueries.listOutgoingFromTo(from, to, limit, offset).mapNotNull {  id ->
                when (id) {
                    is WalletPaymentId.IncomingPaymentId -> throw RuntimeException("unhandled")
                    is WalletPaymentId.LightningOutgoingPaymentId -> lightningOutgoingQueries.getPayment(id.id)
                    is WalletPaymentId.SpliceOutgoingPaymentId -> spliceOutQueries.getSpliceOutPayment(id.id)
                    is WalletPaymentId.ChannelCloseOutgoingPaymentId -> channelCloseQueries.getChannelCloseOutgoingPayment(id.id)
                    is WalletPaymentId.InboundLiquidityOutgoingPaymentId -> inboundLiquidityQueries.get(id.id)
                    is WalletPaymentId.SpliceCpfpOutgoingPaymentId -> cpfpQueries.getCpfp(id.id)
                }?.let {
                    it to metadataQueries.get(id)
                }
            }
        }
    }

    suspend fun listOutgoingFromTo(from: Long, to: Long, limit: Long, offset: Long, sentOnly: Boolean): List<OutgoingPayment> {
        return withContext(Dispatchers.Default) {
            val paymentIds = if (sentOnly) {
                aggregatedQueries.listOutgoingSentFromTo(from, to, limit, offset)
            } else {
                aggregatedQueries.listOutgoingFromTo(from, to, limit, offset)
            }
            paymentIds.mapNotNull {  id ->
                when (id) {
                    is WalletPaymentId.IncomingPaymentId -> null
                    is WalletPaymentId.LightningOutgoingPaymentId -> lightningOutgoingQueries.getPayment(id.id)
                    is WalletPaymentId.SpliceOutgoingPaymentId -> spliceOutQueries.getSpliceOutPayment(id.id)
                    is WalletPaymentId.ChannelCloseOutgoingPaymentId -> channelCloseQueries.getChannelCloseOutgoingPayment(id.id)
                    is WalletPaymentId.InboundLiquidityOutgoingPaymentId -> inboundLiquidityQueries.get(id.id)
                    is WalletPaymentId.SpliceCpfpOutgoingPaymentId -> cpfpQueries.getCpfp(id.id)
                }
            }
        }
    }
}
