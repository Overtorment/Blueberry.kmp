package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadersProgressStoreTest {
    @Test
    fun percent_eta_and_ignore_non_advancing_samples() {
        val store = createHeadersProgressStore()
        assertEquals(0, store.get().downloaded)
        assertEquals(0, store.get().total)
        assertNull(store.get().at)
        assertNull(store.get().etaMs)
        assertEquals(0, store.get().percent)

        store.applyEvent(at = 1000, downloaded = 100, total = 1000, height = 100)
        assertEquals(10, store.get().percent)
        assertNull(store.get().etaMs)

        store.applyEvent(at = 1500, downloaded = 100, total = 1000, height = 100)
        assertNull(store.get().etaMs)
        assertEquals(1500, store.get().at)

        store.applyEvent(at = 2000, downloaded = 200, total = 1000, height = 200)
        assertEquals(8000, store.get().etaMs)

        store.applyEvent(at = 3000, downloaded = 1000, total = 1000, height = 1000)
        assertEquals(100, store.get().percent)
        assertEquals(0, store.get().etaMs)
    }

    @Test
    fun formatEta_and_progressBar_match_helix3() {
        assertEquals("—", formatEta(null))
        assertEquals("done", formatEta(0))
        assertEquals("done", formatEta(-1))
        assertEquals("2s", formatEta(1500))
        assertEquals("1m 5s", formatEta(65_000))

        assertEquals("[░░░░░░░░░░] 0%", progressBar(0, 10))
        assertEquals("[█████░░░░░] 50%", progressBar(50, 10))
        assertEquals("[██████████] 100%", progressBar(100, 10))
        assertEquals("[░░░░░░░░░░] 0%", progressBar(-10, 10))
        assertEquals("[██████████] 100%", progressBar(200, 10))
        assertEquals(10, progressBar(0).count { it == '░' || it == '█' })
    }
}
