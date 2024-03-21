package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import okio.Path

expect val homeDirectory: Path

expect fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver
