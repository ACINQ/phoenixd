package fr.acinq.lightning.bin

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.ChannelException
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.FailureMessage

class InMemoryPaymentsDb : PaymentsDb {
    private val incoming = mutableMapOf<ByteVector32, IncomingPayment>()
    private val outgoing = mutableMapOf<UUID, LightningOutgoingPayment>()
    private val outgoingParts = mutableMapOf<UUID, Pair<UUID, LightningOutgoingPayment.Part>>()
    override suspend fun setLocked(txId: TxId) {}

    override suspend fun addIncomingPayment(preimage: ByteVector32, origin: IncomingPayment.Origin, createdAt: Long): IncomingPayment {
        val paymentHash = Crypto.sha256(preimage).toByteVector32()
        require(!incoming.contains(paymentHash)) { "an incoming payment for $paymentHash already exists" }
        val incomingPayment = IncomingPayment(preimage, origin, null, createdAt)
        incoming[paymentHash] = incomingPayment
        return incomingPayment
    }

    override suspend fun getIncomingPayment(paymentHash: ByteVector32): IncomingPayment? = incoming[paymentHash]

    override suspend fun receivePayment(paymentHash: ByteVector32, receivedWith: List<IncomingPayment.ReceivedWith>, receivedAt: Long) {
        when (val payment = incoming[paymentHash]) {
            null -> Unit // no-op
            else -> incoming[paymentHash] = run {
                payment.copy(
                    received = IncomingPayment.Received(
                        receivedWith = (payment.received?.receivedWith ?: emptySet()) + receivedWith,
                        receivedAt = receivedAt
                    )
                )
            }
        }
    }

    fun listIncomingPayments(count: Int, skip: Int): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .sortedByDescending { it.createdAt }
            .drop(skip)
            .take(count)
            .toList()

    override suspend fun listExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<IncomingPayment> =
        incoming.values
            .asSequence()
            .filter { it.createdAt in fromCreatedAt until toCreatedAt }
            .filter { it.isExpired() }
            .filter { it.received == null }
            .sortedByDescending { it.createdAt }
            .toList()

    override suspend fun removeIncomingPayment(paymentHash: ByteVector32): Boolean {
        val payment = getIncomingPayment(paymentHash)
        return when (payment?.received) {
            null -> incoming.remove(paymentHash) != null
            else -> false // do nothing if payment already partially paid
        }
    }

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        require(!outgoing.contains(outgoingPayment.id)) { "an outgoing payment with id=${outgoingPayment.id} already exists" }
        when (outgoingPayment) {
            is LightningOutgoingPayment -> {
                outgoingPayment.parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
                outgoing[outgoingPayment.id] = outgoingPayment.copy(parts = listOf())
                outgoingPayment.parts.forEach { outgoingParts[it.id] = Pair(outgoingPayment.id, it) }
            }
            is OnChainOutgoingPayment -> {} // we don't persist on-chain payments
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? {
        return outgoing[id]?.let { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            return when (payment.status) {
                is LightningOutgoingPayment.Status.Completed.Succeeded -> payment.copy(parts = parts.filter { it.status is LightningOutgoingPayment.Part.Status.Succeeded })
                else -> payment.copy(parts = parts)
            }
        }
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = LightningOutgoingPayment.Status.Completed.Succeeded.OffChain(preimage = preimage, completedAt = completedAt))
    }

    override suspend fun completeOutgoingPaymentOffchain(id: UUID, finalFailure: FinalFailure, completedAt: Long) {
        require(outgoing.contains(id)) { "outgoing payment with id=$id doesn't exist" }
        val payment = outgoing[id]!!
        outgoing[id] = payment.copy(status = LightningOutgoingPayment.Status.Completed.Failed(reason = finalFailure, completedAt = completedAt))
    }

    override suspend fun addOutgoingLightningParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) {
        require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
        parts.forEach { require(!outgoingParts.contains(it.id)) { "an outgoing payment part with id=${it.id} already exists" } }
        parts.forEach { outgoingParts[it.id] = Pair(parentId, it) }
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, failure: Either<ChannelException, FailureMessage>, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = OutgoingPaymentFailure.convertFailure(failure, completedAt)))
    }

    override suspend fun completeOutgoingLightningPart(partId: UUID, preimage: ByteVector32, completedAt: Long) {
        require(outgoingParts.contains(partId)) { "outgoing payment part with id=$partId doesn't exist" }
        val (parentId, part) = outgoingParts[partId]!!
        outgoingParts[partId] = Pair(parentId, part.copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, completedAt)))
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? {
        return outgoingParts[partId]?.let { (parentId, _) ->
            require(outgoing.contains(parentId)) { "parent outgoing payment with id=$parentId doesn't exist" }
            getLightningOutgoingPayment(parentId)
        }
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> {
        return outgoing.values.filter { it.paymentHash == paymentHash }.map { payment ->
            val parts = outgoingParts.values.filter { it.first == payment.id }.map { it.second }
            payment.copy(parts = parts)
        }
    }
}