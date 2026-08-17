package io.bluewallet.blueberry

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip158.hexToBytes
import io.bluewallet.bip324.Networks
import io.bluewallet.echalote.Echalote
import io.bluewallet.headers.MAINNET_HEADER_CONSENSUS

fun vendorLibraryStatus(): List<String> = listOf(
    vendorStatusLine("headers") {
        "headers: checkpoint ${MAINNET_HEADER_CONSENSUS.checkpoint.height}"
    },
    vendorStatusLine("bip324") {
        "bip324: mainnet port ${Networks.mainnet.defaultPort}"
    },
    vendorStatusLine("bip157") {
        "bip157: NODE_COMPACT_FILTERS $NODE_COMPACT_FILTERS"
    },
    vendorStatusLine("bip158") {
        "bip158: hex 00 size ${hexToBytes("00").size}"
    },
    vendorStatusLine("echalote") {
        "echalote: meek ${Echalote.DEFAULT_MEEK_URL}"
    },
)

internal fun vendorStatusLine(name: String, block: () -> String): String =
    try {
        block()
    } catch (error: Exception) {
        "$name: error ${error.message}"
    }
