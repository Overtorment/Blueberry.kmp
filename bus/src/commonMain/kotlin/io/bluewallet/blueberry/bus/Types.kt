package io.bluewallet.blueberry.bus

enum class ModuleStatus(val wireName: String) {
    STARTING("starting"),
    RUNNING("running"),
    STOPPED("stopped"),
    ERROR("error"),
}

/** Peer work kind for `peers:sockets` counts. */
enum class PeerSocketKind(val wireName: String) {
    PROBE("probe"),
    HDR("hdr"),
    FILT("filt"),
    BLK("blk"),
}

enum class SyncCatchupReason(val wireName: String) {
    HEADERS("headers"),
    FILTERS("filters"),
    BLOCKS("blocks"),
    PEERS("peers"),
}

enum class BroadcastPhase(val wireName: String) {
    WAITING_PEERS("waiting-peers"),
    ATTEMPT("attempt"),
    FAILED_ATTEMPT("failed-attempt"),
    ERROR("error"),
}

data class ModuleStatusPayload(
    val module: String,
    val status: ModuleStatus,
    val detail: String? = null,
)

data class PeersUpdatedPayload(val at: Long)

data class PeersSocketsPayload(
    val at: Long,
    val kind: PeerSocketKind,
    val open: Int,
)

data class HeadersProgressPayload(
    val at: Long,
    val downloaded: Int,
    val total: Int,
    val height: Int,
)

data class FiltersProgressPayload(
    val at: Long,
    val downloaded: Int,
    val total: Int,
)

data class FiltersMatchPayload(
    val height: Int,
    val blockHashInternalHex: String,
)

data class MatchingProgressPayload(
    val at: Long,
    val scanned: Int,
    val total: Int,
)

data class BlocksProgressPayload(
    val at: Long,
    val downloaded: Int,
    val matched: Int,
)

data class SyncIdlePayload(val at: Long)

data class SyncCatchupPayload(
    val at: Long,
    val reason: SyncCatchupReason,
)

data class WalletTxsPayload(val at: Long)

data class BroadcastRequestPayload(
    val id: String,
    val txHex: String,
)

data class BroadcastCancelPayload(val id: String)

data class BroadcastProgressPayload(
    val id: String,
    val phase: BroadcastPhase,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val peer: String? = null,
    val detail: String? = null,
)

sealed class BroadcastDonePayload {
    abstract val id: String

    data class Ok(override val id: String, val peer: String) : BroadcastDonePayload()

    data class Error(override val id: String, val error: String) : BroadcastDonePayload()
}

/**
 * Typed in-process event catalog.
 *
 * Durable facts live in SQLite. Session facts live in payloads.
 * `at` fields are Unix milliseconds.
 *
 * Each [name] matches helix3 `EventMap` keys.
 */
sealed class Event<T>(val name: String) {
    data object ModuleStatus : Event<ModuleStatusPayload>("module:status")

    data object PeersUpdated : Event<PeersUpdatedPayload>("peers:updated")

    data object PeersSockets : Event<PeersSocketsPayload>("peers:sockets")

    data object HeadersProgress : Event<HeadersProgressPayload>("headers:progress")

    data object FiltersProgress : Event<FiltersProgressPayload>("filters:progress")

    data object FiltersMatch : Event<FiltersMatchPayload>("filters:match")

    data object MatchingProgress : Event<MatchingProgressPayload>("matching:progress")

    data object BlocksProgress : Event<BlocksProgressPayload>("blocks:progress")

    data object SyncIdle : Event<SyncIdlePayload>("sync:idle")

    data object SyncCatchup : Event<SyncCatchupPayload>("sync:catchup")

    data object WalletTxs : Event<WalletTxsPayload>("wallet:txs")

    data object BroadcastRequest : Event<BroadcastRequestPayload>("broadcast:request")

    data object BroadcastCancel : Event<BroadcastCancelPayload>("broadcast:cancel")

    data object BroadcastProgress : Event<BroadcastProgressPayload>("broadcast:progress")

    data object BroadcastDone : Event<BroadcastDonePayload>("broadcast:done")
}

/**
 * In-process typed pub/sub.
 *
 * `emit` calls handlers in the same turn. Handlers must be synchronous;
 * thrown errors are swallowed. `on` returns an unsubscribe function.
 */
interface MessageBus {
    fun <T> on(event: Event<T>, handler: (T) -> Unit): () -> Unit

    fun <T> emit(event: Event<T>, payload: T)
}
