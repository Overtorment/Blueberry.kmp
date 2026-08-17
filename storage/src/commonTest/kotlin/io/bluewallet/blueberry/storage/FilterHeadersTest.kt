package io.bluewallet.blueberry.storage

import io.bluewallet.headers.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FilterHeadersTest {
    @Test
    fun append_get_deleteFrom() {
        val db = createSqliteDatabase(":memory:")
        db.filterHeaders.append(
            listOf(
                FilterHeaderRecord(10, hexToBytes("aa".repeat(32))),
                FilterHeaderRecord(11, hexToBytes("bb".repeat(32))),
            ),
        )
        assertContentEquals(hexToBytes("aa".repeat(32)), db.filterHeaders.get(10)!!.header)
        assertEquals(11, db.filterHeaders.tip()!!.height)
        assertEquals(listOf(10, 11), db.filterHeaders.loadRange(10, 11).map { it.height })
        db.filterHeaders.deleteFrom(11)
        assertEquals(10, db.filterHeaders.tip()!!.height)
        db.close()
    }
}
