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

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.bin.db.payments.*
import fr.acinq.lightning.bin.db.payments.LinkTxToPaymentQueries
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.db.*
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.FailureMessage
import fr.acinq.phoenix.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SqlitePaymentsDb(
    loggerFactory: LoggerFactory,
    private val driver: SqlDriver,
) : PaymentsDb {

    private val log = loggerFactory.newLogger(this::class)

    private val database = PaymentsDatabase(
        driver = driver,
        outgoing_payment_partsAdapter = Outgoing_payment_parts.Adapter(
            part_routeAdapter = OutgoingQueries.hopDescAdapter,
            part_status_typeAdapter = EnumColumnAdapter()
        ),
        outgoing_paymentsAdapter = Outgoing_payments.Adapter(
            status_typeAdapter = EnumColumnAdapter(),
            details_typeAdapter = EnumColumnAdapter()
        ),
        incoming_paymentsAdapter = Incoming_payments.Adapter(
            origin_typeAdapter = EnumColumnAdapter(),
            received_with_typeAdapter = EnumColumnAdapter()
        ),
        outgoing_payment_closing_tx_partsAdapter = Outgoing_payment_closing_tx_parts.Adapter(
            part_closing_info_typeAdapter = EnumColumnAdapter()
        ),
        channel_close_outgoing_paymentsAdapter = Channel_close_outgoing_payments.Adapter(
            closing_info_typeAdapter = EnumColumnAdapter()
        ),
        inbound_liquidity_outgoing_paymentsAdapter = Inbound_liquidity_outgoing_payments.Adapter(
            lease_typeAdapter = EnumColumnAdapter()
        )
    )

    internal val inQueries = IncomingQueries(database)
    internal val outQueries = OutgoingQueries(database)
    private val spliceOutQueries = SpliceOutgoingQueries(database)
    private val channelCloseQueries = ChannelCloseOutgoingQueries(database)
    private val cpfpQueries = SpliceCpfpOutgoingQueries(database)
    private val linkTxToPaymentQueries = LinkTxToPaymentQueries(database)
    private val inboundLiquidityQueries = InboundLiquidityQueries(database)

    override suspend fun addOutgoingLightningParts(
        parentId: UUID,
        parts: List<LightningOutgoingPayment.Part>
    ) {
        withContext(Dispatchers.Default) {
            outQueries.addLightningParts(parentId, parts)
        }
    }

    override suspend fun addOutgoingPayment(
        outgoingPayment: OutgoingPayment
    ) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (outgoingPayment) {
                    is LightningOutgoingPayment -> {
                        outQueries.addLightningOutgoingPayment(outgoingPayment)
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
            outQueries.completePayment(id, LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, completedAt))
        }
    }

    override suspend fun completeOutgoingPaymentOffchain(
        id: UUID,
        finalFailure: FinalFailure,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            outQueries.completePayment(id, LightningOutgoingPayment.Status.Completed.Failed(finalFailure, completedAt))
        }
    }

    override suspend fun completeOutgoingLightningPart(
        partId: UUID,
        preimage: ByteVector32,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            outQueries.updateLightningPart(partId, preimage, completedAt)
        }
    }

    override suspend fun completeOutgoingLightningPart(
        partId: UUID,
        failure: Either<ChannelException, FailureMessage>,
        completedAt: Long
    ) {
        withContext(Dispatchers.Default) {
            outQueries.updateLightningPart(partId, failure, completedAt)
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        outQueries.getPaymentStrict(id)
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? = withContext(Dispatchers.Default) {
        outQueries.getPaymentFromPartId(partId)
    }

    // ---- list outgoing

    override suspend fun listLightningOutgoingPayments(
        paymentHash: ByteVector32
    ): List<LightningOutgoingPayment> = withContext(Dispatchers.Default) {
        outQueries.listLightningOutgoingPayments(paymentHash)
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

}
