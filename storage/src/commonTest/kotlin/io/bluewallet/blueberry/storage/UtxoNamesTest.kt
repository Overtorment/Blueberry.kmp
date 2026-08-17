package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtxoNamesTest {
    @Test
    fun get_upsert_delete_list_by_outpoint() {
        val db = createSqliteDatabase(":memory:")
        val out = "aa".repeat(32) + ":0"
        assertNull(db.utxoNames.get(out))
        assertEquals(emptyList(), db.utxoNames.list())
        db.utxoNames.upsert(out, "cold storage")
        assertEquals("cold storage", db.utxoNames.get(out))
        assertEquals(listOf(UtxoNameRow(out, "cold storage")), db.utxoNames.list())
        db.utxoNames.upsert(out, "renamed")
        assertEquals("renamed", db.utxoNames.get(out))
        db.utxoNames.delete(out)
        assertNull(db.utxoNames.get(out))
        assertEquals(emptyList(), db.utxoNames.list())
        db.close()
    }
}
