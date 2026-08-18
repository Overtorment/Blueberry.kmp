package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BirthdayTest {
    @Test
    fun none_pending_freeze_once_garbage_is_none() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        assertFalse(maybeFreezeWalletBirthday(db, 100))
        markWalletBirthdayPending(db)
        assertEquals(WalletBirthdayInspection.Pending, inspectWalletBirthday(db))
        assertTrue(maybeFreezeWalletBirthday(db, 950_123))
        assertEquals(WalletBirthdayInspection.Ok(950_123), inspectWalletBirthday(db))
        assertFalse(maybeFreezeWalletBirthday(db, 950_000))
        assertFalse(maybeFreezeWalletBirthday(db, 960_000))
        assertEquals(WalletBirthdayInspection.Ok(950_123), inspectWalletBirthday(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "nope")
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun compact_filter_from_uses_birthday_floor() {
        val db = createSqliteDatabase(":memory:")
        assertNull(compactFilterFrom(db))
        db.headers.append(
            listOf(
                HeaderWrite(
                    height = 100,
                    hashInternalHex = "aa".repeat(32),
                    header = ByteArray(80),
                ),
            ),
        )
        assertEquals(100, compactFilterFrom(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "150")
        assertEquals(150, compactFilterFrom(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "80")
        assertEquals(100, compactFilterFrom(db))
        db.close()
    }
}
