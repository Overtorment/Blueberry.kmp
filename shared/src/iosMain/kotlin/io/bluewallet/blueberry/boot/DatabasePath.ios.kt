package io.bluewallet.blueberry.boot

import platform.Foundation.NSFileManager

actual fun deleteSqliteDatabaseFiles(path: String) {
    val fm = NSFileManager.defaultManager
    listOf(path, "$path-wal", "$path-shm").forEach { file ->
        if (fm.fileExistsAtPath(file)) {
            fm.removeItemAtPath(file, null)
        }
    }
}
