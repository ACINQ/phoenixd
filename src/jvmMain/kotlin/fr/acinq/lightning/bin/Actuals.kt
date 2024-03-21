package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.phoenix.db.PhoenixDatabase
import okio.Path
import okio.Path.Companion.toPath

actual val homeDirectory: Path = System.getProperty("user.home").toPath()

actual fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver {
    val path = dir / "phoenix.${chain.name.lowercase()}.${nodeId.toHex().take(6)}.db"
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    PhoenixDatabase.Schema.create(driver)
    return driver
}
