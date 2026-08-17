package io.bluewallet.blueberry

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip158.hexToBytes
import io.bluewallet.bip324.Networks
import io.bluewallet.headers.MAINNET_HEADER_CONSENSUS
import kotlin.test.Test
import kotlin.test.assertEquals

class VendorLibrariesTest {

    @Test
    fun libraries_resolve_on_commonTest() {
        assertEquals(665_280L, MAINNET_HEADER_CONSENSUS.checkpoint.height)
        assertEquals(8333, Networks.mainnet.defaultPort)
        assertEquals(64, NODE_COMPACT_FILTERS)
        assertEquals(1, hexToBytes("00").size)
    }
}
