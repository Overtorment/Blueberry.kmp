package io.bluewallet.blueberry.wallet

import java.security.SecureRandom

actual fun fillRandomBytes(dest: ByteArray) {
    SecureRandom().nextBytes(dest)
}
