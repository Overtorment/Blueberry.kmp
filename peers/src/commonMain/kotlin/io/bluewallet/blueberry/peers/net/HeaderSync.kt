package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.GetHeadersPayload
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.answerPing
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.headers.BlockHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

const val SESSION_BUSY_ERROR = "session busy"

private val ZERO_HASH = ByteArray(32)

sealed class HeaderBatchResult {
    data class Ok(val startHeight: Int, val headers: List<BlockHeader>) : HeaderBatchResult()
    data class Err(val error: String) : HeaderBatchResult()
}

class HeaderRequestResult(
    val startHeight: Int,
    val headers: List<BlockHeader>,
)

class HeaderFetchOptions(
    val locatorHashes: List<ByteArray>,
    val stopHash: ByteArray? = null,
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
)

class HeaderSyncOptions(
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val locatorHashes: List<ByteArray>,
    val stopHash: ByteArray? = null,
    val connect: TcpConnect,
    val requestHeaders: (suspend (ByteDuplex, Int, List<ByteArray>, ByteArray) -> HeaderRequestResult)? = null,
)

class OpenedHeaderSession(
    val startHeight: Int,
    val requestHeaders: suspend (List<ByteArray>, ByteArray) -> HeaderRequestResult,
    val close: suspend () -> Unit,
)

class HeaderSessionPoolOptions(
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val connect: TcpConnect? = null,
    val openSession: (suspend (String, Int) -> OpenedHeaderSession)? = null,
    val onOpenCount: ((Int) -> Unit)? = null,
    val max: Int? = null,
)

interface HeaderSessionPool {
    fun has(host: String, port: Int): Boolean
    fun isBusy(host: String, port: Int): Boolean
    fun isFull(): Boolean
    suspend fun fetchBatch(host: String, port: Int, options: HeaderFetchOptions): HeaderBatchResult
    suspend fun drop(host: String, port: Int)
    suspend fun closeAll()
}

private fun peerKey(host: String, port: Int) = "$host:$port"

private fun wireToLib(header: io.bluewallet.bip324.BlockHeader): BlockHeader =
    BlockHeader(
        version = header.version,
        previousBlockHash = header.previousBlockHash.copyOf(),
        merkleRoot = header.merkleRoot.copyOf(),
        timestamp = header.timestamp.toLong() and 0xffffffffL,
        bits = header.bits.toLong() and 0xffffffffL,
        nonce = header.nonce.toLong() and 0xffffffffL,
    )

private suspend fun connectOrAbort(
    connect: TcpConnect,
    host: String,
    port: Int,
): ByteDuplex {
    val pending = CompletableDeferred<ByteDuplex>()
    val connectJob = CoroutineScope(coroutineContext).launch {
        try {
            pending.complete(connect(host, port))
        } catch (e: CancellationException) {
            pending.cancel(e)
            throw e
        } catch (e: Throwable) {
            pending.completeExceptionally(e)
        }
    }
    try {
        return pending.await()
    } catch (e: CancellationException) {
        connectJob.invokeOnCompletion {
            val d = runCatching { pending.getCompleted() }.getOrNull() ?: return@invokeOnCompletion
            CoroutineScope(Dispatchers.Default).launch { runCatching { d.close() } }
        }
        throw e
    }
}

private suspend fun handshake(duplex: ByteDuplex, port: Int): Pair<Protocol, Int> {
    val protocol = Protocol.connect(
        duplex,
        ProtocolOptions(role = Role.Initiator, network = Networks.mainnet),
    )
    val result = completeVersionHandshake(
        protocol,
        VersionHandshakeOptions(port = port, name = APP_NAME, version = APP_VERSION),
    )
    return protocol to result.startHeight
}

private suspend fun requestHeaderBatch(
    protocol: Protocol,
    startHeight: Int,
    locatorHashes: List<ByteArray>,
    stopHash: ByteArray,
): HeaderRequestResult {
    protocol.writeMessage(
        Message.GetHeaders(
            GetHeadersPayload(version = 70_016, locatorHashes = locatorHashes, stopHash = stopHash),
        ),
    )
    while (true) {
        val message = protocol.readMessage()
        if (message is Message.Headers) {
            return HeaderRequestResult(
                startHeight = startHeight,
                headers = message.payload.headers.map(::wireToLib),
            )
        }
        answerPing(protocol, message)
    }
}

private fun timeoutMessage(label: String, ms: Long) = "$label timed out after ${ms}ms"

suspend fun fetchHeadersBatch(
    host: String,
    port: Int,
    options: HeaderSyncOptions,
): HeaderBatchResult {
    val connectTimeoutMs = options.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val headersTimeoutMs = options.headersTimeoutMs ?: Config.headerSyncTimeoutMs
    val stopHash = options.stopHash ?: ZERO_HASH
    var duplex: ByteDuplex? = null
    return try {
        withTimeout(connectTimeoutMs) {
            duplex = connectOrAbort(options.connect, host, port)
        }
        val live = duplex!!
        if (options.requestHeaders != null) {
            val result = withTimeout(headersTimeoutMs) {
                options.requestHeaders.invoke(live, port, options.locatorHashes, stopHash)
            }
            HeaderBatchResult.Ok(result.startHeight, result.headers)
        } else {
            val (protocol, startHeight) = withTimeout(connectTimeoutMs) {
                handshake(live, port)
            }
            val result = withTimeout(headersTimeoutMs) {
                requestHeaderBatch(protocol, startHeight, options.locatorHashes, stopHash)
            }
            HeaderBatchResult.Ok(result.startHeight, result.headers)
        }
    } catch (e: TimeoutCancellationException) {
        HeaderBatchResult.Err(timeoutMessage("header connect/handshake", connectTimeoutMs))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        HeaderBatchResult.Err(e.message ?: e.toString())
    } finally {
        try {
            withContext(NonCancellable) { duplex?.close() }
        } catch (_: Throwable) {
        }
    }
}

private class LiveSession(
    val host: String,
    val port: Int,
    val startHeight: Int,
    val requestHeaders: suspend (List<ByteArray>, ByteArray) -> HeaderRequestResult,
    val close: suspend () -> Unit,
) {
    var busy = false
}

private class PoolSnapshot(
    val live: Set<String>,
    val connecting: Set<String>,
    val busy: Set<String>,
    val open: Int,
)

private class PoolSnapshotBox {
    @Volatile var value = PoolSnapshot(emptySet(), emptySet(), emptySet(), 0)
}

fun createHeaderSessionPool(
    poolOptions: HeaderSessionPoolOptions = HeaderSessionPoolOptions(),
): HeaderSessionPool {
    val defaultConnectTimeoutMs = poolOptions.connectTimeoutMs ?: Config.peerProbeTimeoutMs
    val defaultHeadersTimeoutMs = poolOptions.headersTimeoutMs ?: Config.headerSyncTimeoutMs
    val max = maxOf(1, poolOptions.max ?: Config.headerRacePeers * 2)
    val onOpenCount = poolOptions.onOpenCount
    val connect = poolOptions.connect
    val sessions = LinkedHashMap<String, LiveSession>()
    val connecting = LinkedHashSet<String>()
    val mutex = Mutex()
    var opening = 0
    var lastOpenCount = -1
    var epoch = 0
    val snap = PoolSnapshotBox()

    fun openCountLocked() = sessions.size + opening

    fun publishLocked() {
        val busy = HashSet<String>(connecting)
        for ((key, session) in sessions) {
            if (session.busy) busy.add(key)
        }
        snap.value = PoolSnapshot(
            live = sessions.keys.toSet(),
            connecting = connecting.toSet(),
            busy = busy,
            open = openCountLocked(),
        )
    }

    fun notifyOpenCountLocked() {
        if (onOpenCount == null) return
        val n = openCountLocked()
        if (n == lastOpenCount) return
        lastOpenCount = n
        onOpenCount.invoke(n)
    }

    suspend fun dropSession(host: String, port: Int) {
        val session = mutex.withLock {
            val removed = sessions.remove(peerKey(host, port)) ?: return@withLock null
            publishLocked()
            notifyOpenCountLocked()
            removed
        } ?: return
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    suspend fun openLive(host: String, port: Int, connectTimeoutMs: Long): LiveSession {
        val opened = poolOptions.openSession
        if (opened != null) {
            val session = opened(host, port)
            return LiveSession(
                host = host,
                port = port,
                startHeight = session.startHeight,
                requestHeaders = session.requestHeaders,
                close = session.close,
            )
        }
        val tcp = connect ?: error("HeaderSessionPool.connect required without openSession")
        var duplex: ByteDuplex? = null
        try {
            return withTimeout(connectTimeoutMs) {
                duplex = connectOrAbort(tcp, host, port)
                val liveDuplex = duplex!!
                val (protocol, startHeight) = handshake(liveDuplex, port)
                LiveSession(
                    host = host,
                    port = port,
                    startHeight = startHeight,
                    requestHeaders = { locator, stop ->
                        requestHeaderBatch(protocol, startHeight, locator, stop)
                    },
                    close = {
                        try {
                            protocol.close()
                        } catch (_: Throwable) {
                            liveDuplex.close()
                        }
                    },
                )
            }
        } catch (e: Throwable) {
            if (duplex != null) {
                try {
                    withContext(NonCancellable) { duplex!!.close() }
                } catch (_: Throwable) {
                }
            }
            throw e
        }
    }

    return object : HeaderSessionPool {
        override fun has(host: String, port: Int): Boolean {
            val key = peerKey(host, port)
            val view = snap.value
            return view.live.contains(key) || view.connecting.contains(key)
        }

        override fun isBusy(host: String, port: Int): Boolean {
            val key = peerKey(host, port)
            return snap.value.busy.contains(key)
        }

        override fun isFull(): Boolean = snap.value.open >= max

        override suspend fun fetchBatch(
            host: String,
            port: Int,
            options: HeaderFetchOptions,
        ): HeaderBatchResult {
            val key = peerKey(host, port)
            val connectTimeoutMs = options.connectTimeoutMs ?: defaultConnectTimeoutMs
            val headersTimeoutMs = options.headersTimeoutMs ?: defaultHeadersTimeoutMs
            val stopHash = options.stopHash ?: ZERO_HASH
            val started = mutex.withLock { epoch }
            var session: LiveSession? = mutex.withLock { sessions[key] }
            try {
                if (session == null) {
                    val busy = mutex.withLock {
                        if (connecting.contains(key)) return@withLock true
                        if (openCountLocked() >= max) return@withLock true
                        connecting.add(key)
                        opening++
                        publishLocked()
                        notifyOpenCountLocked()
                        false
                    }
                    if (busy) return HeaderBatchResult.Err(SESSION_BUSY_ERROR)
                    try {
                        session = openLive(host, port, connectTimeoutMs)
                        val closed = mutex.withLock {
                            sessions[key] = session!!
                            publishLocked()
                            epoch != started
                        }
                        if (closed) {
                            dropSession(host, port)
                            return HeaderBatchResult.Err("session closed")
                        }
                    } finally {
                        mutex.withLock {
                            connecting.remove(key)
                            opening--
                            publishLocked()
                            notifyOpenCountLocked()
                        }
                    }
                }
                val live = session!!
                val claimed = mutex.withLock {
                    if (live.busy) {
                        false
                    } else {
                        live.busy = true
                        publishLocked()
                        true
                    }
                }
                if (!claimed) return HeaderBatchResult.Err(SESSION_BUSY_ERROR)
                try {
                    val result = withTimeout(headersTimeoutMs) {
                        live.requestHeaders(options.locatorHashes, stopHash)
                    }
                    return HeaderBatchResult.Ok(result.startHeight, result.headers)
                } finally {
                    mutex.withLock {
                        live.busy = false
                        publishLocked()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                dropSession(host, port)
                return HeaderBatchResult.Err(timeoutMessage("header download", headersTimeoutMs))
            } catch (e: CancellationException) {
                dropSession(host, port)
                throw e
            } catch (e: Throwable) {
                dropSession(host, port)
                return HeaderBatchResult.Err(e.message ?: e.toString())
            }
        }

        override suspend fun drop(host: String, port: Int) {
            dropSession(host, port)
        }

        override suspend fun closeAll() {
            val open = mutex.withLock {
                epoch++
                publishLocked()
                sessions.values.toList()
            }
            for (session in open) {
                dropSession(session.host, session.port)
            }
            mutex.withLock {
                publishLocked()
                notifyOpenCountLocked()
            }
        }
    }
}
