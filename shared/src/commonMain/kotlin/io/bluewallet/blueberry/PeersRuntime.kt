package io.bluewallet.blueberry

import io.bluewallet.blueberry.boot.loadSyncFromYear
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.filters.modules.FiltersDownloadOptions
import io.bluewallet.blueberry.filters.modules.FiltersMatchingOptions
import io.bluewallet.blueberry.filters.modules.createFiltersDownloadModule
import io.bluewallet.blueberry.filters.modules.createFiltersMatchingModule
import io.bluewallet.blueberry.headers.consensusForYear
import io.bluewallet.blueberry.headers.modules.ChainHeadersOptions
import io.bluewallet.blueberry.headers.modules.createChainHeadersModule
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.PeersDiscoveryOptions
import io.bluewallet.blueberry.peers.modules.createPeersDiscoveryModule
import io.bluewallet.blueberry.peers.net.createPlatformNet
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.createWallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val headersStore: HeadersProgressStore = createHeadersProgressStore()
    val filtersStore: FiltersProgressStore = createFiltersProgressStore()
    val matchingStore: MatchingProgressStore = createMatchingProgressStore()
    private val net = createPlatformNet()
    private val discovery: Module = createPeersDiscoveryModule(
        ModuleContext(bus, db),
        PeersDiscoveryOptions(net = net),
    )
    private var headers: Module? = null
    private var filters: Module? = null
    private var matching: Module? = null
    private var unbind: (() -> Unit)? = null
    @Volatile private var alive = true

    init {
        hydratePeers(db, store)
        hydrateHeaders(db, headersStore)
        hydrateFilters(db, filtersStore)
        hydrateMatching(db, matchingStore)
    }

    suspend fun start() {
        if (!alive) return
        val unbindPeers = bindPeerSocketEvents(bus, db, store)
        val unbindHeaders = bindHeaderProgressEvents(bus, db, headersStore)
        val unbindFilters = bindFilterProgressEvents(bus, db, filtersStore)
        val unbindMatching = bindMatchingProgressEvents(bus, db, matchingStore)
        unbind = {
            unbindPeers()
            unbindHeaders()
            unbindFilters()
            unbindMatching()
        }
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        hydratePeers(db, store)
        hydrateHeaders(db, headersStore)
        hydrateFilters(db, filtersStore)
        hydrateMatching(db, matchingStore)
        if (!alive) {
            unbind?.invoke()
            unbind = null
            return
        }
        try {
            discovery.start()
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
        try {
            val headersModule = createChainHeadersModule(
                ModuleContext(bus, db),
                ChainHeadersOptions(
                    net = net,
                    consensus = consensusForYear(loadSyncFromYear(db)),
                ),
            )
            headers = headersModule
            headersModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "chain-headers",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            val filtersModule = createFiltersDownloadModule(
                ModuleContext(bus, db),
                FiltersDownloadOptions(net = net),
            )
            filters = filtersModule
            filtersModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "filters-download",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        try {
            val matchingModule = createFiltersMatchingModule(
                ModuleContext(bus, db),
                FiltersMatchingOptions(
                    wallet = withContext(Dispatchers.Default) { createWallet(db) },
                ),
            )
            matching = matchingModule
            matchingModule.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "filters-matching",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
        if (!alive) {
            matching?.stop()
            filters?.stop()
            headers?.stop()
            discovery.stop()
        }
    }

    fun stop() {
        alive = false
        matching?.stop()
        matching = null
        filters?.stop()
        filters = null
        headers?.stop()
        headers = null
        discovery.stop()
        unbind?.invoke()
        unbind = null
    }
}
