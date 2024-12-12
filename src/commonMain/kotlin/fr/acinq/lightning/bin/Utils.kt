package fr.acinq.lightning.bin

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.serialization.OutputExtensions.writeUuid
import fr.acinq.lightning.utils.UUID

fun ByteVector32.deriveUUID(): UUID = UUID.fromBytes(this.take(16).toByteArray())

// TODO: use standard Uuid once migrated to kotlin 2
fun UUID.toByteArray() =
    ByteArrayOutput().run {
        writeUuid(this@toByteArray)
        toByteArray()
    }