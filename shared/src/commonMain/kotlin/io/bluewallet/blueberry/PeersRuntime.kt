package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.PeersDiscoveryOptions
import io.bluewallet.blueberry.peers.modules.createPeersDiscoveryModule
import io.bluewallet.blueberry.peers.net.createPlatformNet
import io.bluewallet.blueberry.storage.Database
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

fun bindPeerSocketEvents(bus: MessageBus, db: Database, store: PeerSocketsStore): () -> Unit {
    val a = bus.on(Event.PeersUpdated) { hydratePeers(db, store) }
    val b = bus.on(Event.PeersSockets) { store.applyEvent(it.kind, it.open) }
    return {
        a()
        b()
    }
}

class PeersRuntime(private val db: Database) {
    val bus: MessageBus = createMessageBus()
    val store: PeerSocketsStore = createPeerSocketsStore()
    private val module: Module = createPeersDiscoveryModule(
        ModuleContext(bus, db),
        PeersDiscoveryOptions(net = createPlatformNet()),
    )
    private var unbind: (() -> Unit)? = null
    @Volatile private var alive = true

    suspend fun start() {
        if (!alive) return
        unbind = bindPeerSocketEvents(bus, db, store)
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        hydratePeers(db, store)
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        try {
            module.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "peers-discovery",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        if (!alive) module.stop()
    }

    fun stop() {
        alive = false
        module.stop()
        unbind?.invoke()
        unbind = null
    }
}
