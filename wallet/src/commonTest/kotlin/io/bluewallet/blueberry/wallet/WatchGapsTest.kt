package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchGapsTest {
    @Test
    fun load_defaults_and_persists() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_KEY))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_INTERNAL_KEY))
        saveWatchGaps(db, WatchGaps(60, 40))
        assertEquals(WatchGaps(60, 40), loadWatchGaps(db))
        db.close()
    }

    @Test
    fun load_clamps_absurd_counts() {
        val db = createSqliteDatabase(":memory:")
        db.keyValue.set(WATCH_EXTERNAL_KEY, "1000000000")
        db.keyValue.set(WATCH_INTERNAL_KEY, "-1")
        assertEquals(WatchGaps(10_000, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals("10000", db.keyValue.get(WATCH_EXTERNAL_KEY))
        db.close()
    }

    @Test
    fun load_parseInt_sign_and_ascii_digits_only() {
        val db = createSqliteDatabase(":memory:")
        db.keyValue.set(WATCH_EXTERNAL_KEY, "+60")
        db.keyValue.set(WATCH_INTERNAL_KEY, "６０")
        assertEquals(WatchGaps(60, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals("60", db.keyValue.get(WATCH_EXTERNAL_KEY))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_INTERNAL_KEY))
        db.close()
    }

    @Test
    fun load_parseInt_overflow_to_infinity_defaults() {
        val db = createSqliteDatabase(":memory:")
        val overflow = "9".repeat(400)
        db.keyValue.set(WATCH_EXTERNAL_KEY, overflow)
        db.keyValue.set(WATCH_INTERNAL_KEY, overflow)
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_KEY))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_INTERNAL_KEY))
        db.close()
    }

    @Test
    fun load_parseInt_prefix_and_huge_values() {
        val db = createSqliteDatabase(":memory:")
        db.keyValue.set(WATCH_EXTERNAL_KEY, "10000x")
        db.keyValue.set(WATCH_INTERNAL_KEY, "999999999999")
        assertEquals(WatchGaps(10_000, 10_000), loadWatchGaps(db))
        assertEquals("10000", db.keyValue.get(WATCH_EXTERNAL_KEY))
        assertEquals("10000", db.keyValue.get(WATCH_INTERNAL_KEY))
        db.close()
    }

    @Test
    fun grows_when_used_index_in_danger_zone() {
        val r = growWatchGapsIfNeeded(WatchGaps(40, 40), listOf(25), emptyList(), 20)
        assertTrue(r.grew)
        assertEquals(WatchGaps(60, 40), r.gaps)
    }

    @Test
    fun no_grow_below_danger_zone() {
        val r = growWatchGapsIfNeeded(WatchGaps(40, 40), listOf(19), listOf(10), 20)
        assertFalse(r.grew)
        assertEquals(WatchGaps(40, 40), r.gaps)
    }

    @Test
    fun growth_stops_at_cap() {
        val atCap = growWatchGapsIfNeeded(WatchGaps(10_000, 10_000), listOf(9_999), emptyList(), 100)
        assertFalse(atCap.grew)
        val nearCap = growWatchGapsIfNeeded(WatchGaps(9_950, 40), listOf(9_900), emptyList(), 100)
        assertTrue(nearCap.grew)
        assertEquals(WatchGaps(10_000, 40), nearCap.gaps)
    }
}
