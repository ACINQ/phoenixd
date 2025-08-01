package fr.acinq.phoenixd

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.phoenixd.conf.EnvVars.PHOENIX_DATADIR
import fr.acinq.phoenixd.db.migrations.v3.AfterVersion3
import fr.acinq.phoenixd.db.migrations.v4.AfterVersion4
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.posix.setenv

@OptIn(ExperimentalForeignApi::class)
actual val datadir: Path = setenv("KTOR_LOG_LEVEL", "WARN", 1)
    .let {
        getenv(PHOENIX_DATADIR)?.toKString()?.let { Path(it) } ?: Path(getenv("HOME")?.toKString()!!, ".phoenix") }

actual fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver {
    val chainName = when (chain) {
        is Chain.Testnet3 -> "testnet"
        else -> chain.name.lowercase()
    }
    return NativeSqliteDriver(
        schema = PhoenixDatabase.Schema,
        name = "phoenix.$chainName.${nodeId.toHex().take(6)}.db",
        maxReaderConnections = 1,
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir.toString(), foreignKeyConstraints = true)) },
        AfterVersion3,
        AfterVersion4
    )
}
