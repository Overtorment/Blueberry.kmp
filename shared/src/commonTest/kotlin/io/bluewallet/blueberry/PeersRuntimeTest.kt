package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PeersRuntimeTest {
    @Test
    fun bindPeerSocketEvents_hydrates_and_applies() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, 0uL, false, false, null),
        )
        val store = createPeerSocketsStore()
        val off = bindPeerSocketEvents(bus, db, store)
        hydratePeers(db, store)
        assertEquals(1, store.get().known)
        bus.emit(Event.PeersSockets, PeersSocketsPayload(1, PeerSocketKind.PROBE, 2))
        assertEquals(2, store.get().probe)
        db.peers.upsert(PeerWrite("9.9.9.9", 8333, 0uL, false, false, null))
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
        assertEquals(2, store.get().known)
        off()
        db.close()
    }

    @Test
    fun bindHeaderProgressEvents_hydrates_from_db_then_progress() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.headers.append(
            listOf(
                io.bluewallet.blueberry.storage.HeaderWrite(
                    height = 10,
                    hashInternalHex = "aa".repeat(32),
                    header = ByteArray(80),
                    cumulativeWork = com.ionspin.kotlin.bignum.integer.BigInteger.fromInt(10),
                ),
                io.bluewallet.blueberry.storage.HeaderWrite(
                    height = 11,
                    hashInternalHex = "bb".repeat(32),
                    header = ByteArray(80),
                    cumulativeWork = com.ionspin.kotlin.bignum.integer.BigInteger.fromInt(11),
                ),
            ),
        )
        val store = createHeadersProgressStore()
        val off = bindHeaderProgressEvents(bus, db, store)
        hydrateHeaders(db, store)
        assertEquals(1, store.get().downloaded)
        assertEquals(1, store.get().total)
        assertEquals(11, store.get().height)
        bus.emit(
            Event.HeadersProgress,
            io.bluewallet.blueberry.bus.HeadersProgressPayload(3, 1, 500, 11),
        )
        assertEquals(1, store.get().downloaded)
        assertEquals(500, store.get().total)
        assertEquals(11, store.get().height)
        off()
        db.close()
    }
}
