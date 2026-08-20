package io.bluewallet.blueberry.headers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckpointTest {
    @Test
    fun year_2019_is_default_height_556416() {
        assertEquals(2019, DEFAULT_CHECKPOINT_YEAR)
        assertEquals(556416, CHECKPOINT_HEIGHT)
        assertEquals(556416, checkpointForYear(2019).height)
        assertEquals(556416L, BLUEBERRY_HEADER_CONSENSUS.checkpoint.height)
        assertEquals(556416, checkpointDbRecord().height)
        assertEquals(556416, checkpointSeedRecord().height)
    }

    @Test
    fun unknown_year_throws() {
        val ex = assertFailsWith<IllegalArgumentException> { checkpointForYear(1999) }
        assertTrue(ex.message!!.contains("unknown checkpoint year: 1999"))
    }

    @Test
    fun every_onboarding_year_has_a_checkpoint() {
        for (year in 2009..2026) {
            val seed = checkpointSeedRecord(year)
            assertEquals(checkpointForYear(year).height, seed.height)
            assertEquals(80, seed.headerHex.length / 2)
        }
    }
}
