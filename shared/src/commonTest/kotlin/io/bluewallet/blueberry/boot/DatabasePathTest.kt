package io.bluewallet.blueberry.boot

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabasePathTest {
    @Test
    fun joins_directory_and_filename() {
        assertEquals("/tmp/data/blueberry.sqlite", blueberrySqlitePath("/tmp/data"))
        assertEquals("/tmp/data/blueberry.sqlite", blueberrySqlitePath("/tmp/data/"))
    }
}
