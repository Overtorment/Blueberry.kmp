package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

internal expect fun openSqliteDriver(path: String): SqlDriver
