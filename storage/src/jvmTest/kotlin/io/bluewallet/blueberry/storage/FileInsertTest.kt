package io.bluewallet.blueberry.storage

import kotlin.io.path.createTempFile
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileInsertTest {
    @Test
    fun insert_returns_true_then_false_on_file_database() {
        val path = createTempFile(prefix = "blueberry-storage", suffix = ".sqlite").pathString
        val db = createSqliteDatabase(path)

        assertTrue(db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32))))
        assertFalse(db.matchedBlocks.insert(MatchedBlock(10, "aa".repeat(32))))

        assertTrue(
            db.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(1))),
        )
        assertFalse(
            db.blocks.insert(DownloadedBlock(10, "aa".repeat(32), byteArrayOf(1))),
        )

        db.close()
    }
}
