package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase
import fr.acinq.phoenix.db.PaymentsDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.setenv

@OptIn(ExperimentalForeignApi::class)
actual val homeDirectory: Path = setenv("KTOR_LOG_LEVEL", "WARN", 1).let { getenv("HOME")?.toKString()!!.toPath() }

actual fun createAppDbDriver(dir: Path): SqlDriver {
    return NativeSqliteDriver(ChannelsDatabase.Schema, "phoenix.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir.toString())) }
    )
}

actual fun createPaymentsDbDriver(dir: Path): SqlDriver {
    return NativeSqliteDriver(PaymentsDatabase.Schema, "payments.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = dir.toString())) }
    )
}