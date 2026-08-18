package io.bluewallet.blueberry.wallet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun fillRandomBytes(dest: ByteArray) {
    if (dest.isEmpty()) return
    dest.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, dest.size.toULong(), pinned.addressOf(0))
        require(status == 0) { "SecRandomCopyBytes failed" }
    }
}
