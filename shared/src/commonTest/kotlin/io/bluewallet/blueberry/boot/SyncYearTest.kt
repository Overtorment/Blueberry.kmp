package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncYearTest {
    @Test
    fun listCheckpointYears_is_sorted_contiguous_2009_to_2026() {
        val years = listCheckpointYears()
        assertEquals(2009, years.first())
        assertEquals(2026, years.last())
        assertEquals(2026, latestCheckpointYear())
        assertEquals(18, years.size)
        assertEquals(2019, DEFAULT_CHECKPOINT_YEAR)
        for (i in 1 until years.size) {
            assertEquals(years[i - 1] + 1, years[i])
        }
    }

    @Test
    fun parseSyncFromYear_accepts_known_years_and_rejects_garbage() {
        assertEquals(2019, parseSyncFromYear("2019"))
        assertEquals(2015, parseSyncFromYear(" 2015 "))
        assertNull(parseSyncFromYear(null))
        assertNull(parseSyncFromYear(""))
        assertNull(parseSyncFromYear("1999"))
        assertNull(parseSyncFromYear("2019.0"))
        assertNull(parseSyncFromYear("019"))
        assertNull(parseSyncFromYear("abc"))
    }

    @Test
    fun save_load_round_trip_and_invalid_kv_reads_as_missing() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertFailsWith<IllegalArgumentException> { loadSyncFromYear(db) }.also {
            assertTrue(it.message!!.contains("sync_from_year"))
        }

        saveSyncFromYear(db, 2015)
        assertEquals("2015", db.keyValue.get(SYNC_FROM_YEAR_KEY))
        assertEquals(2015, loadSyncFromYear(db))
        assertEquals(SyncFromYearInspection.Ok(2015), inspectSyncFromYear(db))

        db.keyValue.set(SYNC_FROM_YEAR_KEY, "nope")
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertFailsWith<IllegalArgumentException> { saveSyncFromYear(db, 1999) }.also {
            assertTrue(it.message!!.contains("unknown"))
        }
        db.close()
    }
}
