package io.bluewallet.blueberry.bus

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBusTest {
    @Test
    fun delivers_payload_to_subscribers() {
        val bus = createMessageBus()
        val seen = mutableListOf<PeersUpdatedPayload>()
        bus.on(Event.PeersUpdated) { seen.add(it) }
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = 42))
        assertEquals(listOf(PeersUpdatedPayload(at = 42)), seen)
    }

    @Test
    fun unsubscribe_stops_delivery() {
        val bus = createMessageBus()
        var count = 0
        val off = bus.on(Event.ModuleStatus) { count++ }
        bus.emit(
            Event.ModuleStatus,
            ModuleStatusPayload(module = "x", status = ModuleStatus.RUNNING),
        )
        off()
        bus.emit(
            Event.ModuleStatus,
            ModuleStatusPayload(module = "x", status = ModuleStatus.STOPPED),
        )
        assertEquals(1, count)
    }

    @Test
    fun handler_errors_do_not_block_other_listeners() {
        val bus = createMessageBus()
        val seen = mutableListOf<String>()
        bus.on(Event.PeersUpdated) { error("boom") }
        bus.on(Event.PeersUpdated) { seen.add(it.at.toString()) }
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = 1))
        assertEquals(listOf("1"), seen)
    }

    @Test
    fun delivers_sync_idle() {
        val bus = createMessageBus()
        val seen = mutableListOf<Long>()
        bus.on(Event.SyncIdle) { seen.add(it.at) }
        bus.emit(Event.SyncIdle, SyncIdlePayload(at = 99))
        assertEquals(listOf(99L), seen)
    }
}
