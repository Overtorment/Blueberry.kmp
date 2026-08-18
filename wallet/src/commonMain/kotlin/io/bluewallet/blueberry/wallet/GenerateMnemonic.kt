package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.MnemonicCode

fun generateMnemonic12(): String {
    val entropy = ByteArray(16)
    fillRandomBytes(entropy)
    return MnemonicCode.toMnemonics(entropy).joinToString(" ")
}
