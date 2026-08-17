package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueTest {
    @Test
    fun get_set_key_value() {
        val db = createSqliteDatabase(":memory:")
        assertNull(db.keyValue.get("watch_external"))
        db.keyValue.set("watch_external", "40")
        db.keyValue.set("watch_internal", "40")
        assertEquals("40", db.keyValue.get("watch_external"))
        db.keyValue.set("watch_external", "60")
        assertEquals("60", db.keyValue.get("watch_external"))
        db.close()
    }
}
