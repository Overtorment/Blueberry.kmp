package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateMnemonicTest {
    @Test
    fun yields_twelve_valid_english_words() {
        val mnemonic = generateMnemonic12()
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        MnemonicCode.validate(mnemonic)
        assertTrue(words.all { it == it.lowercase() })
    }
}
