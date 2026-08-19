package io.bluewallet.blueberry.boot

fun blueberrySqlitePath(directory: String): String {
    val trimmed = directory.trimEnd('/', '\\')
    return "$trimmed/blueberry.sqlite"
}

/** Deletes the SQLite file and WAL/SHM sidecars. Used by debug Clear storage. */
expect fun deleteSqliteDatabaseFiles(path: String)
