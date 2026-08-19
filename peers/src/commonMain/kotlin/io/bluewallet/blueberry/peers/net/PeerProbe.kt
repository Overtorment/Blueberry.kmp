package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext

sealed class ProbeResult {
    data class Ok(val peers: List<PeerCandidate>, val services: ULong) : ProbeResult()
    data class Err(val error: String) : ProbeResult()
}

data class HandshakeResult(val peers: List<PeerCandidate>, val services: ULong)

class ProbeOptions(
    val timeoutMs: Long? = null,
    val connect: TcpConnect,
    val handshakeAndGetAddr: (suspend (ByteDuplex, Int) -> HandshakeResult)? = null,
)

private suspend fun defaultHandshakeAndGetAddr(duplex: ByteDuplex, port: Int): HandshakeResult {
    val protocol = Protocol.connect(
        duplex,
        ProtocolOptions(role = Role.Initiator, network = Networks.mainnet),
    )
    val result = completeVersionHandshake(
        protocol,
        VersionHandshakeOptions(port = port, name = APP_NAME, version = APP_VERSION),
    )
    return HandshakeResult(emptyList(), result.services)
}

@OptIn(ExperimentalCoroutinesApi::class)
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

suspend fun probePeer(host: String, port: Int, options: ProbeOptions): ProbeResult {
    val timeoutMs = options.timeoutMs ?: Config.peerProbeTimeoutMs
    val handshake = options.handshakeAndGetAddr ?: { d, p -> defaultHandshakeAndGetAddr(d, p) }
    var duplex: ByteDuplex? = null
    return try {
        withTimeout(timeoutMs) {
            val connected = connectOrAbort(options.connect, host, port)
            duplex = connected
            val hs = handshake(connected, port)
            ProbeResult.Ok(hs.peers, hs.services)
        }
    } catch (e: TimeoutCancellationException) {
        ProbeResult.Err("probe timed out after ${timeoutMs}ms")
    } catch (e: Throwable) {
        ProbeResult.Err(e.message ?: e.toString())
    } finally {
        try {
            withContext(NonCancellable) { duplex?.close() }
        } catch (_: Throwable) {
        }
    }
}
