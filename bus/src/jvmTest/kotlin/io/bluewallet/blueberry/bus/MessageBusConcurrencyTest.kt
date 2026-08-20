package io.bluewallet.blueberry.bus

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageBusConcurrencyTest {
    @Test
    fun concurrent_subscribe_then_emit_delivers_to_all() {
        val bus = createMessageBus()
        val threads = 64
        val seen = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val subscribed = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                bus.on(Event.PeersUpdated) { seen.incrementAndGet() }
                subscribed.countDown()
            }
        }
        start.countDown()
        assertTrue(subscribed.await(10, TimeUnit.SECONDS))
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(1))
        pool.shutdownNow()
        assertEquals(threads, seen.get())
    }
}
