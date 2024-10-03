package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import fr.acinq.lightning.bin.conf.EnvVars.PHOENIX_DATADIR
import fr.acinq.phoenix.db.PhoenixDatabase
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.setenv

@OptIn(ExperimentalForeignApi::class)
actual val datadir: Path = setenv("KTOR_LOG_LEVEL", "WARN", 1)
    .let {
        getenv(PHOENIX_DATADIR)?.toKString()?.toPath() ?: getenv("HOME")?.toKString()!!.toPath().div(".phoenix") }

actual fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver {
    val chainName = when (chain) {
        is Chain.Testnet3 -> "testnet"
        else -> chain.name.lowercase()
    }
    return NativeSqliteDriver(PhoenixDatabase.Schema, "phoenix.$chainName.${nodeId.toHex().take(6)}.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir.toString())) }
    )
}
