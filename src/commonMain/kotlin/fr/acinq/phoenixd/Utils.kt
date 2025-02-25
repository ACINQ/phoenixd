package fr.acinq.phoenixd

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.serialization.OutputExtensions.writeUuid
import fr.acinq.lightning.utils.UUID
import kotlinx.io.bytestring.ByteString

fun ByteVector32.deriveUUID(): UUID = UUID.fromBytes(this.take(16).toByteArray())

// TODO: use standard Uuid once migrated to kotlin 2
fun UUID.toByteArray() =
    ByteArrayOutput().run {
        writeUuid(this@toByteArray)
        toByteArray()
    }

fun ByteArray.hmacSha256(secret: ByteArray): ByteArray = Digest.sha256().hmac(secret, this, 64)