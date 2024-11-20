package fr.acinq.lightning.bin

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.utils.UUID

fun ByteVector32.deriveUUID(): UUID = UUID.fromBytes(this.take(16).toByteArray())