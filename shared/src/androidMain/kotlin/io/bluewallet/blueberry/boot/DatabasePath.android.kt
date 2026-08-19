package io.bluewallet.blueberry.boot

import java.io.File

actual fun deleteSqliteDatabaseFiles(path: String) {
    listOf("", "-wal", "-shm").forEach { suffix ->
        File(path + suffix).delete()
    }
}
