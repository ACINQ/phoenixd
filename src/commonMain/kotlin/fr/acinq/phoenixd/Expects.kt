package fr.acinq.phoenixd

import app.cash.sqldelight.db.SqlDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import kotlinx.io.files.Path

expect val datadir: Path

expect fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver

/**
 * Like Bitcoin Core, set a 077 umask so that all files and directories created by phoenixd are only accessible by the current user.
 * On the JVM, this is done by the start script instead.
 */
expect fun setPrivateUmask()

/** Sets the posix permissions of [path] to [mode]. Best effort: failures (e.g. read-only file system) are ignored. */
expect fun setPermissions(path: Path, mode: Int)
