package fr.acinq.lightning.bin

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.conf.EnvVars.PHOENIX_DATADIR
import fr.acinq.lightning.bin.db.migrations.v3.afterVersion3
import fr.acinq.lightning.bin.db.migrations.v4.afterVersion4
import fr.acinq.phoenix.db.PhoenixDatabase
import okio.Path
import okio.Path.Companion.toPath
import java.util.*

actual val datadir: Path = (System.getenv()[PHOENIX_DATADIR]?.toPath() ?: System.getProperty("user.home").toPath().div(".phoenix"))

actual fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver {
    val chainName = when (chain) {
        is Chain.Testnet3 -> "testnet"
        else -> chain.name.lowercase()
    }
    val path = dir / "phoenix.$chainName.${nodeId.toHex().take(6)}.db"

    // Initial schema version wasn't set, so we need to set it manually
    JdbcSqliteDriver("jdbc:sqlite:$path").let { driver ->
        if (driver.getVersion() == 0L && driver.isPhoenixDbInitialized()) {
            driver.setVersion(1)
        }
        driver.close()
    }

    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:$path",
        properties = Properties().apply { put("foreign_keys", "true") },
        schema = PhoenixDatabase.Schema,
        migrateEmptySchema = false,
        afterVersion3(addEnclosingTransaction = true),
        afterVersion4(addEnclosingTransaction = true)
    )
    return driver
}

private fun JdbcSqliteDriver.isPhoenixDbInitialized(): Boolean {
    return executeQuery(
        null,
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='local_channels'",
        { sqlCursor: SqlCursor -> sqlCursor.next() },
        0
    ).value
}

private fun JdbcSqliteDriver.getVersion(): Long {
    val mapper = { cursor: SqlCursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
    }
    return executeQuery(null, "PRAGMA user_version", mapper, 0, null).value ?: 0L
}

private fun JdbcSqliteDriver.setVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0, null).value
}
