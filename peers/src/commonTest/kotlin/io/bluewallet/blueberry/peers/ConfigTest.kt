package io.bluewallet.blueberry.peers

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun helix3_probe_defaults() {
        assertEquals(3_000L, Config.peerProbeTimeoutMs)
        assertEquals(30, Config.peerConcurrency)
        assertEquals(30_000L, Config.headerSyncTimeoutMs)
        assertEquals(10, Config.headerRacePeers)
    }
}
