package fr.acinq.phoenixd

import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import kotlinx.io.files.Path

expect val datadir: Path

expect fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver
