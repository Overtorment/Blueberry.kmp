package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Message
import io.bluewallet.bip324.NetworkAddress
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionPayload
import io.bluewallet.bip324.pairedByteDuplexes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerProbeTest {
    @Test
    fun maps_connect_failure_to_err() = runBlocking {
        val result = probePeer(
            "1.2.3.4",
            8333,
            ProbeOptions(
                timeoutMs = 1000,
                connect = { _, _ -> error("ECONNREFUSED") },
                handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
            ),
        )
        assertTrue(result is ProbeResult.Err)
        assertTrue((result as ProbeResult.Err).error.contains("ECONNREFUSED"))
    }

    @Test
    fun timeout_aborts_slow_connect_and_closes_duplex() = runBlocking {
        var closed = false
        val result = probePeer(
            "1.2.3.4",
            8333,
            ProbeOptions(
                timeoutMs = 20,
                connect = { _, _ ->
                    withContext(NonCancellable) { delay(200) }
                    val inner = stubDuplex()
                    object : ByteDuplex {
                        override suspend fun read(n: Int) = inner.read(n)
                        override suspend fun write(bytes: ByteArray) = inner.write(bytes)
                        override suspend fun close() {
                            closed = true
                            inner.close()
                        }
                    }
                },
                handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
            ),
        )
        assertTrue(result is ProbeResult.Err)
        assertTrue(
            (result as ProbeResult.Err).error.contains("timed out") ||
                result.error.contains("aborted"),
        )
        delay(250)
        assertTrue(closed)
    }

    @Test
    fun succeeds_after_verack_without_waiting_for_getaddr() = runBlocking {
        coroutineScope {
            val (clientSide, serverSide) = pairedByteDuplexes()
            val server = async {
                val protocol = Protocol.connect(
                    serverSide,
                    ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
                )
                val version = protocol.readMessage()
                check(version is Message.Version)
                protocol.writeMessage(
                    Message.Version(
                        VersionPayload(
                            version = 70_016,
                            services = 1033uL,
                            timestamp = 0,
                            receiver = NetworkAddress(0uL, ByteArray(16), 8333),
                            sender = NetworkAddress(0uL, ByteArray(16), 0),
                            nonce = 1uL,
                            userAgent = "/test/",
                            startHeight = 0,
                            relay = false,
                        ),
                    ),
                )
                protocol.writeMessage(Message.Verack)
                while (true) {
                    val msg = protocol.readMessage()
                    if (msg.command == "verack") break
                }
                delay(50)
                protocol.close()
            }
            val result = probePeer(
                "127.0.0.1",
                8333,
                ProbeOptions(
                    timeoutMs = 2_000,
                    connect = { _, _ -> clientSide },
                ),
            )
            assertTrue(result is ProbeResult.Ok)
            val ok = result as ProbeResult.Ok
            assertEquals(emptyList(), ok.peers)
            assertEquals(1033uL, ok.services)
            server.await()
        }
    }
}
