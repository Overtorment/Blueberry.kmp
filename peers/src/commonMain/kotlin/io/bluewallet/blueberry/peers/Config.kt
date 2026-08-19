package io.bluewallet.blueberry.peers

object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerConcurrency: Int = 30
    const val headerSyncTimeoutMs: Long = 30_000
    const val headerRacePeers: Int = 10
}
