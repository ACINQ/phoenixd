package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.phoenix.db.ChannelsDatabase
import fr.acinq.phoenix.db.PaymentsDatabase
import okio.Path
import okio.Path.Companion.toPath

actual val homeDirectory: Path = System.getProperty("user.home").toPath()

actual fun createAppDbDriver(dir: Path): SqlDriver {
    val path = dir / "phoenix.db"
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    ChannelsDatabase.Schema.create(driver)
    return driver
}

actual fun createPaymentsDbDriver(dir: Path): SqlDriver {
    val path = dir / "payments.db"
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    PaymentsDatabase.Schema.create(driver)
    return driver
}
