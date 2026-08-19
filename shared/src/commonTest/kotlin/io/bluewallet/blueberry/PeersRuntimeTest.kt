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
}
