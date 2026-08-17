package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerServicesTest {
    @Test
    fun services_round_trip_full_unsigned_64_bit_range() {
        val high = 1uL shl 63
        val max = ULong.MAX_VALUE
        assertEquals(0uL, fromSqliteServices(toSqliteServices(0uL)))
        assertEquals(2049uL, fromSqliteServices(toSqliteServices(2049uL)))
        assertEquals(high, fromSqliteServices(toSqliteServices(high)))
        assertEquals(max, fromSqliteServices(toSqliteServices(max)))
        assertEquals(-(1L shl 63), toSqliteServices(high))
    }
}
