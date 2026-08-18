package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals

class HexTest {
    @Test
    fun script_hex_and_round_trip() {
        assertEquals("0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2", scriptHex(hexToBytes("0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2")))
        assertEquals(1, hexToBytes("00").size)
    }
}
