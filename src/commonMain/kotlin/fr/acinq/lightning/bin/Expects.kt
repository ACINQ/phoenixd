package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import okio.Path

expect val homeDirectory: Path

expect fun createAppDbDriver(dir: Path): SqlDriver
