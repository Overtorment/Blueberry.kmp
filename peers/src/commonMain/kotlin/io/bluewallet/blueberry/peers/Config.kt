package io.bluewallet.blueberry.peers

object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerConcurrency: Int = 30
    const val headerSyncTimeoutMs: Long = 30_000
    const val headerRacePeers: Int = 10
    const val filterSyncTimeoutMs: Long = 30_000
    const val filterConcurrency: Int = 10
    const val filterHeaderBatchSize: Int = 2000
    const val filterBatchSize: Int = 100
}
