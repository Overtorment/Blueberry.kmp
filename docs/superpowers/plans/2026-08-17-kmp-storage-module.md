# KMP storage module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `:storage` with SQLDelight, the helix3 SQLite schema, and the helix3 `Database` repository API.

**Architecture:** A KMP module opens a platform SQLite driver, applies the helix3 tables, and wraps SQLDelight queries in Kotlin repositories. Callers use `createSqliteDatabase(path)` only. The generated class is `StorageDb`.

**Tech Stack:** Kotlin 2.4.10, SQLDelight 2.3.2, `android-driver` / `native-driver` / `sqlite-driver`, `io.bluewallet:bitcoin-headers`.

## Global Constraints

- Schema SQL matches helix3 `src/db/schema.ts` (tables, columns, defaults, primary keys, indexes).
- Public API matches helix3 `src/db/types.ts`. Behaviour matches helix3 `src/db/sqlite-database.ts`.
- `createSqliteDatabase(path: String): Database`. `:memory:` is in-memory. Any other path is a file path on Android, iOS, and JVM.
- SQLDelight generated class name is `StorageDb`. Package is `io.bluewallet.blueberry.storage`.
- After open: apply schema, then `PRAGMA journal_mode = WAL`, `PRAGMA synchronous = NORMAL`, `PRAGMA wal_autocheckpoint = 10000`.
- Second open of the same Blueberry.kmp file must succeed. Do not run a bare `CREATE TABLE` that fails when tables exist.
- Do not open helix3 `.sqlite` files. Do not make files portable between helix3 and Blueberry.kmp.
- Do not set helix3 `cache_size`, `mmap_size`, or `SQLITE_FCNTL_PERSIST_WAL`.
- `services` is `ULong`. Encode with `PeerServices` before bind. `cumulativeWork` is `com.ionspin.kotlin.bignum.integer.BigInteger`.
- Heights, ports, and indexes are `Int`. Timestamps and `netDeltaSats` are `Long`. Blobs are `ByteArray`.
- Header work uses bitcoin-headers. Invalid header bytes yield work `1`.
- `ensureCheckpoint` mismatch message contains `checkpoint mismatch` and `Delete blueberry.data/blueberry.sqlite`.
- Do not change Click me, Compose UI, or vendor status.
- Do not add Room or a bundled SQLite driver on all platforms.
- Header tests use bitcoin-headers mainnet checkpoint height `665280`, not helix3 year-2019 `556416`.
- Pass `./gradlew :storage:jvmTest`.
- Do not commit unless the user asks.

## File structure

```
storage/build.gradle.kts
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Schema.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/KeyValue.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/UtxoNames.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Peers.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Headers.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/FilterHeaders.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Filters.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/MatchedBlocks.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Blocks.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/ParsedBlocks.sq
storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Transactions.sq
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/Types.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/PeerServices.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/HeaderWork.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/Time.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SchemaApply.kt
storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt
storage/src/androidMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.android.kt
storage/src/androidMain/kotlin/io/bluewallet/blueberry/storage/Time.android.kt
storage/src/iosMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.ios.kt
storage/src/iosMain/kotlin/io/bluewallet/blueberry/storage/Time.ios.kt
storage/src/jvmMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.jvm.kt
storage/src/jvmMain/kotlin/io/bluewallet/blueberry/storage/Time.jvm.kt
storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/*.kt
storage/src/jvmTest/kotlin/io/bluewallet/blueberry/storage/FileReopenTest.kt
```

Behaviour source for repository SQL: `/home/bigboss/Code/helix3/src/db/sqlite-database.ts`.

---

### Task 1: `:storage` module, schema, and drivers

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `storage/build.gradle.kts`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Schema.sq`
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.kt`
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SchemaApply.kt`
- Create: `storage/src/androidMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.android.kt`
- Create: `storage/src/iosMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.ios.kt`
- Create: `storage/src/jvmMain/kotlin/io/bluewallet/blueberry/storage/SqliteDriver.jvm.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/SchemaTest.kt`
- Test: `storage/src/jvmTest/kotlin/io/bluewallet/blueberry/storage/FileReopenTest.kt`

**Interfaces:**
- Consumes: helix3 `schema.ts` SQL; SQLDelight 2.3.2
- Produces: `internal expect fun openSqliteDriver(path: String): SqlDriver`; `internal fun applySchema(driver: SqlDriver)`; `internal fun applyPragmas(driver: SqlDriver)`; generated `StorageDb`

- [ ] **Step 1: Write the failing schema test**

Create `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/SchemaTest.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    @Test
    fun tables_columns_and_indexes_match_helix3() {
        val driver = openSqliteDriver(":memory:")
        applySchema(driver)
        applyPragmas(driver)
        try {
            assertEquals(
                listOf(
                    "blocks",
                    "filter_headers",
                    "filters",
                    "filters_unscanned",
                    "headers",
                    "key_value",
                    "matched_blocks",
                    "parsed_blocks",
                    "peers",
                    "transactions",
                    "utxo_names",
                ),
                tableNames(driver),
            )
            assertEquals(
                listOf(
                    "host", "port", "services", "alive", "used_for_blocks",
                    "last_probed_at", "created_at", "updated_at",
                ),
                columnNames(driver, "peers"),
            )
            assertEquals(
                listOf("height", "hash_internal_hex", "header", "cumulative_work"),
                columnNames(driver, "headers"),
            )
            assertEquals(listOf("height", "header"), columnNames(driver, "filter_headers"))
            assertEquals(
                listOf("height", "block_hash_internal_hex", "filter"),
                columnNames(driver, "filters"),
            )
            assertEquals(listOf("height"), columnNames(driver, "filters_unscanned"))
            assertEquals(
                listOf("height", "block_hash_internal_hex"),
                columnNames(driver, "matched_blocks"),
            )
            assertEquals(
                listOf("height", "block_hash_internal_hex", "block"),
                columnNames(driver, "blocks"),
            )
            assertEquals(listOf("height"), columnNames(driver, "parsed_blocks"))
            assertEquals(
                listOf(
                    "txid", "height", "tx_index", "block_hash_internal_hex",
                    "tx", "net_delta_sats",
                ),
                columnNames(driver, "transactions"),
            )
            assertEquals(listOf("key", "value"), columnNames(driver, "key_value"))
            assertEquals(listOf("outpoint", "name"), columnNames(driver, "utxo_names"))
            assertEquals(
                listOf("height", "block_hash_internal_hex"),
                indexColumns(driver, "filters_height_hash"),
            )
            assertEquals(
                listOf("hash_internal_hex"),
                indexColumns(driver, "headers_hash_internal_hex"),
            )
            assertEquals(
                listOf("alive", "used_for_blocks"),
                indexColumns(driver, "peers_alive_used"),
            )
        } finally {
            driver.close()
        }
    }
}

private fun tableNames(driver: SqlDriver): List<String> =
    queryStrings(
        driver,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
    )

private fun columnNames(driver: SqlDriver, table: String): List<String> =
    queryStrings(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")

private fun indexColumns(driver: SqlDriver, index: String): List<String> =
    queryStrings(driver, "SELECT name FROM pragma_index_info('$index') ORDER BY seqno")

private fun queryStrings(driver: SqlDriver, sql: String): List<String> {
    return driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) {
                out.add(cursor.getString(0)!!)
            }
            QueryResult.Value(out)
        },
        parameters = 0,
    ).value
}
```

Create `storage/src/jvmTest/kotlin/io/bluewallet/blueberry/storage/FileReopenTest.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import kotlin.io.path.createTempFile
import kotlin.io.path.pathString
import kotlin.test.Test

class FileReopenTest {
    @Test
    fun second_open_of_same_file_succeeds() {
        val file = createTempFile(prefix = "blueberry-storage", suffix = ".sqlite")
        val path = file.pathString
        val first = openSqliteDriver(path)
        applySchema(first)
        applyPragmas(first)
        first.close()
        val second = openSqliteDriver(path)
        applySchema(second)
        applyPragmas(second)
        second.close()
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.SchemaTest`

Expected: FAIL because `:storage` does not exist or `openSqliteDriver` is missing.

- [ ] **Step 3: Add Gradle module and SQLDelight schema**

In `gradle/libs.versions.toml` add version `sqldelight = "2.3.2"`.

Add libraries:

```toml
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
```

Add plugin:

```toml
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

In `settings.gradle.kts` add `include(":storage")` next to `include(":shared")`.

Create `storage/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { }
    jvm()
    android {
        namespace = "io.bluewallet.blueberry.storage"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.bitcoin.headers)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

sqldelight {
    databases {
        create("StorageDb") {
            packageName.set("io.bluewallet.blueberry.storage")
        }
    }
}
```

Create `Schema.sq` with this exact SQL (no `AS` adapters):

```sql
CREATE TABLE peers (
  host TEXT NOT NULL,
  port INTEGER NOT NULL,
  services INTEGER NOT NULL DEFAULT 0,
  alive INTEGER NOT NULL DEFAULT 0,
  used_for_blocks INTEGER NOT NULL DEFAULT 0,
  last_probed_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (host, port)
);

CREATE TABLE headers (
  height INTEGER PRIMARY KEY,
  hash_internal_hex TEXT NOT NULL,
  header BLOB NOT NULL,
  cumulative_work TEXT NOT NULL DEFAULT '0'
);

CREATE TABLE filter_headers (
  height INTEGER PRIMARY KEY,
  header BLOB NOT NULL
);

CREATE TABLE filters (
  height INTEGER PRIMARY KEY,
  block_hash_internal_hex TEXT NOT NULL,
  filter BLOB NOT NULL
);

CREATE TABLE filters_unscanned (
  height INTEGER PRIMARY KEY
);

CREATE TABLE matched_blocks (
  height INTEGER PRIMARY KEY,
  block_hash_internal_hex TEXT NOT NULL
);

CREATE TABLE blocks (
  height INTEGER PRIMARY KEY,
  block_hash_internal_hex TEXT NOT NULL,
  block BLOB NOT NULL
);

CREATE TABLE parsed_blocks (
  height INTEGER PRIMARY KEY
);

CREATE TABLE transactions (
  txid TEXT PRIMARY KEY,
  height INTEGER NOT NULL,
  tx_index INTEGER NOT NULL,
  block_hash_internal_hex TEXT NOT NULL,
  tx BLOB NOT NULL,
  net_delta_sats INTEGER NOT NULL
);

CREATE TABLE key_value (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE utxo_names (
  outpoint TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE INDEX headers_hash_internal_hex
  ON headers(hash_internal_hex);
CREATE INDEX filters_height_hash
  ON filters(height, block_hash_internal_hex);
CREATE INDEX peers_alive_used
  ON peers(alive, used_for_blocks);
```

Create `SqliteDriver.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

internal expect fun openSqliteDriver(path: String): SqlDriver
```

Create `SchemaApply.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal fun applySchema(driver: SqlDriver) {
    val exists = driver.executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='peers' LIMIT 1",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 0,
    ).value
    if (!exists) {
        StorageDb.Schema.create(driver)
    }
}

internal fun applyPragmas(driver: SqlDriver) {
    driver.execute(null, "PRAGMA journal_mode = WAL", 0)
    driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
    driver.execute(null, "PRAGMA wal_autocheckpoint = 10000", 0)
}
```

JVM driver (`SqliteDriver.jvm.kt`):

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

internal actual fun openSqliteDriver(path: String): SqlDriver {
    val url = if (path == ":memory:") JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path"
    return JdbcSqliteDriver(url)
}
```

Android driver (`SqliteDriver.android.kt`) — open the file path, not `Context.getDatabasePath`:

```kotlin
package io.bluewallet.blueberry.storage

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

internal actual fun openSqliteDriver(path: String): SqlDriver {
    val sqlite = if (path == ":memory:") {
        SQLiteDatabase.create(null)
    } else {
        SQLiteDatabase.openOrCreateDatabase(path, null)
    }
    return AndroidSqliteDriver(FrameworkSQLiteDatabase(sqlite))
}
```

If `AndroidSqliteDriver(SupportSQLiteDatabase)` is not in 2.3.2, wrap a `SupportSQLiteOpenHelper` that opens `File(path)` (or `:memory:`). Do not require `Context` for `createSqliteDatabase(path)`.

iOS driver (`SqliteDriver.ios.kt`):

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

internal actual fun openSqliteDriver(path: String): SqlDriver {
    val memory = path == ":memory:"
    return NativeSqliteDriver(
        DatabaseConfiguration(
            name = if (memory) "blueberry-memory.sqlite" else path,
            version = 1,
            create = { },
            upgrade = { _, _, _ -> },
            inMemory = memory,
        ),
    )
}
```

Do not pass `StorageDb.Schema` into the driver constructor. `applySchema` owns create.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.SchemaTest --tests io.bluewallet.blueberry.storage.FileReopenTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 2: Public types and PeerServices

**Files:**
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/Types.kt`
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/PeerServices.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/PeerServicesTest.kt`

**Interfaces:**
- Consumes: helix3 `types.ts`, `peer-services.ts`
- Produces: all public types below; `toSqliteServices(services: ULong): Long`; `fromSqliteServices(stored: Long): ULong`

- [ ] **Step 1: Write the failing PeerServices test**

```kotlin
package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerServicesTest {
    @Test
    fun services_round_trip_full_unsigned_64_bit_range() {
        val high = 1uL shl 63
        val max = ULong.MAX_VALUE
        assertEquals(0uL, fromSqliteServices(toSqliteServices(0uL)))
        assertEquals(2049uL, fromSqliteServices(toSqliteServices(2049uL)))
        assertEquals(high, fromSqliteServices(toSqliteServices(high)))
        assertEquals(max, fromSqliteServices(toSqliteServices(max)))
        assertEquals(-(1L shl 63), toSqliteServices(high))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.PeerServicesTest`

Expected: FAIL because `toSqliteServices` is not defined.

- [ ] **Step 3: Write types and PeerServices**

Create `PeerServices.kt`:

```kotlin
package io.bluewallet.blueberry.storage

fun toSqliteServices(services: ULong): Long = services.toLong()

fun fromSqliteServices(stored: Long): ULong = stored.toULong()
```

Create `Types.kt` with these exact names:

```kotlin
package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger

data class Peer(
    val host: String,
    val port: Int,
    val services: ULong,
    val alive: Boolean,
    val usedForBlocks: Boolean,
    val lastProbedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PeerWrite(
    val host: String,
    val port: Int,
    val services: ULong,
    val alive: Boolean,
    val usedForBlocks: Boolean,
    val lastProbedAt: Long?,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

data class AliveServiceOptions(val unusedForBlocks: Boolean = false)

interface PeersRepository {
    fun upsert(peer: PeerWrite)
    fun list(): List<Peer>
    fun count(): Int
    fun listAlive(): List<Peer>
    fun listAliveWithServices(
        serviceBits: ULong,
        limit: Int,
        options: AliveServiceOptions? = null,
    ): List<Peer>
    fun listWithServices(serviceBits: ULong, limit: Int): List<Peer>
    fun listProbeQueue(limit: Int): List<Peer>
    fun markProbed(host: String, port: Int, at: Long)
    fun markAlive(host: String, port: Int, alive: Boolean)
    fun markUsedForBlocks(host: String, port: Int)
}

data class HeaderRecord(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
)

data class StoredHeader(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
    val cumulativeWork: BigInteger,
)

data class HeaderWrite(
    val height: Int,
    val hashInternalHex: String,
    val header: ByteArray,
    val cumulativeWork: BigInteger? = null,
)

interface HeadersRepository {
    fun ensureCheckpoint(checkpoint: HeaderRecord)
    fun tip(): StoredHeader?
    fun count(): Int
    fun minHeight(): Int?
    fun get(height: Int): StoredHeader?
    fun heightForHashInternal(hashInternalHex: String): Int?
    fun loadRange(fromHeight: Int, toHeight: Int): List<StoredHeader>
    fun loadAll(): List<StoredHeader>
    fun loadFrom(height: Int): List<StoredHeader>
    fun append(headers: List<HeaderWrite>)
    fun replaceAfter(commonAncestorHeight: Int, headers: List<HeaderWrite>)
}

data class FilterHeaderRecord(val height: Int, val header: ByteArray)
data class FilterRecord(val height: Int, val blockHashInternalHex: String, val filter: ByteArray)
data class HeightRange(val from: Int, val to: Int)

interface FilterHeadersRepository {
    fun tip(): FilterHeaderRecord?
    fun get(height: Int): FilterHeaderRecord?
    fun minHeight(): Int?
    fun loadRange(fromHeight: Int, toHeight: Int): List<FilterHeaderRecord>
    fun append(rows: List<FilterHeaderRecord>)
    fun deleteFrom(height: Int)
}

interface FiltersRepository {
    fun count(): Int
    fun countInRange(from: Int, to: Int): Int
    fun minHeight(): Int?
    fun maxHeight(): Int?
    fun has(height: Int): Boolean
    fun get(height: Int): FilterRecord?
    fun hashAt(height: Int): String?
    fun firstHashMismatch(from: Int, to: Int): Int?
    fun missingRanges(from: Int, to: Int, maxSpan: Int): List<HeightRange>
    fun completeInRange(from: Int, to: Int): Boolean
    fun append(rows: List<FilterRecord>)
    fun listNeedingMatch(limit: Int): List<FilterRecord>
    fun countScanned(): Int
    fun markScanned(heights: List<Int>)
    fun markUnscanned(heights: List<Int>)
    fun markUnscannedFrom(fromHeight: Int)
    fun deleteFrom(height: Int)
}

interface KeyValueRepository {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

data class UtxoNameRow(val outpoint: String, val name: String)

interface UtxoNamesRepository {
    fun get(outpoint: String): String?
    fun upsert(outpoint: String, name: String)
    fun delete(outpoint: String)
    fun list(): List<UtxoNameRow>
}

data class MatchedBlock(val height: Int, val blockHashInternalHex: String)
data class DownloadedBlock(val height: Int, val blockHashInternalHex: String, val block: ByteArray)

interface MatchedBlocksRepository {
    fun insert(block: MatchedBlock): Boolean
    fun get(height: Int): MatchedBlock?
    fun count(): Int
    fun listNeedingDownload(limit: Int): List<MatchedBlock>
}

interface BlocksRepository {
    fun count(): Int
    fun has(height: Int): Boolean
    fun get(height: Int): DownloadedBlock?
    fun insert(block: DownloadedBlock): Boolean
    fun listNeedingParse(limit: Int): List<DownloadedBlock>
}

data class StoredTx(
    val txid: String,
    val height: Int,
    val txIndex: Int,
    val blockHashInternalHex: String,
    val tx: ByteArray,
    val netDeltaSats: Long,
)

interface ParsedBlocksRepository {
    fun has(height: Int): Boolean
    fun mark(height: Int)
    fun count(): Int
    fun clearFrom(fromHeight: Int)
}

data class TxSetFingerprint(val count: Int, val netDeltaSum: Long, val newestTxid: String?)

interface TransactionsRepository {
    fun upsert(tx: StoredTx)
    fun list(): List<StoredTx>
    fun count(): Int
    fun fingerprint(): TxSetFingerprint
    fun minHeight(): Int?
    fun get(txid: String): StoredTx?
    fun setNetDelta(txid: String, netDeltaSats: Long)
}

data class WipeFiltersFromOptions(val prevHeaderHeight: Int? = null)

interface Database {
    val peers: PeersRepository
    val headers: HeadersRepository
    val filterHeaders: FilterHeadersRepository
    val filters: FiltersRepository
    val matchedBlocks: MatchedBlocksRepository
    val blocks: BlocksRepository
    val parsedBlocks: ParsedBlocksRepository
    val transactions: TransactionsRepository
    val keyValue: KeyValueRepository
    val utxoNames: UtxoNamesRepository
    fun transaction(fn: () -> Unit)
    fun rewindAfter(ancestorHeight: Int)
    fun wipeFiltersFrom(height: Int, options: WipeFiltersFromOptions? = null)
    fun close()
}

fun createSqliteDatabase(path: String): Database = error("not implemented")
```

Keep `createSqliteDatabase` as a stub until Task 3. If the compiler forbids a stub next to the real function later, delete the stub in Task 3.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.PeerServicesTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 3: `createSqliteDatabase`, key_value, utxo_names

**Files:**
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/Time.kt`
- Create: `storage/src/androidMain/kotlin/io/bluewallet/blueberry/storage/Time.android.kt`
- Create: `storage/src/iosMain/kotlin/io/bluewallet/blueberry/storage/Time.ios.kt`
- Create: `storage/src/jvmMain/kotlin/io/bluewallet/blueberry/storage/Time.jvm.kt`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/KeyValue.sq`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/UtxoNames.sq`
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/Types.kt` (remove stub `createSqliteDatabase`)
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/KeyValueTest.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/UtxoNamesTest.kt`

**Interfaces:**
- Consumes: `openSqliteDriver`, `applySchema`, `applyPragmas`, `Database`
- Produces: `fun createSqliteDatabase(path: String): Database` with working `keyValue` and `utxoNames`. Other repositories throw `NotImplementedError` until later tasks.

- [ ] **Step 1: Write the failing tests**

`KeyValueTest.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueTest {
    @Test
    fun get_set_key_value() {
        val db = createSqliteDatabase(":memory:")
        assertNull(db.keyValue.get("watch_external"))
        db.keyValue.set("watch_external", "40")
        db.keyValue.set("watch_internal", "40")
        assertEquals("40", db.keyValue.get("watch_external"))
        db.keyValue.set("watch_external", "60")
        assertEquals("60", db.keyValue.get("watch_external"))
        db.close()
    }
}
```

`UtxoNamesTest.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtxoNamesTest {
    @Test
    fun get_upsert_delete_list_by_outpoint() {
        val db = createSqliteDatabase(":memory:")
        val out = "aa".repeat(32) + ":0"
        assertNull(db.utxoNames.get(out))
        assertEquals(emptyList(), db.utxoNames.list())
        db.utxoNames.upsert(out, "cold storage")
        assertEquals("cold storage", db.utxoNames.get(out))
        assertEquals(listOf(UtxoNameRow(out, "cold storage")), db.utxoNames.list())
        db.utxoNames.upsert(out, "renamed")
        assertEquals("renamed", db.utxoNames.get(out))
        db.utxoNames.delete(out)
        assertNull(db.utxoNames.get(out))
        assertEquals(emptyList(), db.utxoNames.list())
        db.close()
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.KeyValueTest --tests io.bluewallet.blueberry.storage.UtxoNamesTest`

Expected: FAIL (`createSqliteDatabase` still errors).

- [ ] **Step 3: Implement open path and the two repositories**

`Time.kt`:

```kotlin
package io.bluewallet.blueberry.storage

internal expect fun currentTimeMillis(): Long
```

`Time.jvm.kt` and `Time.android.kt`:

```kotlin
package io.bluewallet.blueberry.storage

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()
```

`Time.ios.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
```

`KeyValue.sq`:

```sql
get:
SELECT value FROM key_value WHERE key = ?;

set:
INSERT INTO key_value(key, value) VALUES (?, ?)
ON CONFLICT(key) DO UPDATE SET value = excluded.value;
```

`UtxoNames.sq`:

```sql
get:
SELECT name FROM utxo_names WHERE outpoint = ?;

upsert:
INSERT INTO utxo_names(outpoint, name) VALUES (?, ?)
ON CONFLICT(outpoint) DO UPDATE SET name = excluded.name;

delete:
DELETE FROM utxo_names WHERE outpoint = ?;

list:
SELECT outpoint, name FROM utxo_names ORDER BY outpoint;
```

`SqliteDatabase.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import app.cash.sqldelight.db.SqlDriver

fun createSqliteDatabase(path: String): Database {
    val driver = openSqliteDriver(path)
    applySchema(driver)
    applyPragmas(driver)
    return SqliteDatabase(driver, StorageDb(driver))
}

internal class SqliteDatabase(
    private val driver: SqlDriver,
    private val storageDb: StorageDb,
) : Database {
    override val keyValue = object : KeyValueRepository {
        override fun get(key: String): String? =
            storageDb.keyValueQueries.get(key).executeAsOneOrNull()
        override fun set(key: String, value: String) {
            storageDb.keyValueQueries.set(key, value)
        }
    }

    override val utxoNames = object : UtxoNamesRepository {
        override fun get(outpoint: String): String? =
            storageDb.utxoNamesQueries.get(outpoint).executeAsOneOrNull()
        override fun upsert(outpoint: String, name: String) {
            storageDb.utxoNamesQueries.upsert(outpoint, name)
        }
        override fun delete(outpoint: String) {
            storageDb.utxoNamesQueries.delete(outpoint)
        }
        override fun list(): List<UtxoNameRow> =
            storageDb.utxoNamesQueries.list().executeAsList()
                .map { UtxoNameRow(it.outpoint, it.name) }
    }

    override val peers: PeersRepository get() = notReady()
    override val headers: HeadersRepository get() = notReady()
    override val filterHeaders: FilterHeadersRepository get() = notReady()
    override val filters: FiltersRepository get() = notReady()
    override val matchedBlocks: MatchedBlocksRepository get() = notReady()
    override val blocks: BlocksRepository get() = notReady()
    override val parsedBlocks: ParsedBlocksRepository get() = notReady()
    override val transactions: TransactionsRepository get() = notReady()

    override fun transaction(fn: () -> Unit) {
        if (driver.currentTransaction() != null) {
            fn()
            return
        }
        storageDb.transaction { fn() }
    }

    override fun rewindAfter(ancestorHeight: Int) = notReady()
    override fun wipeFiltersFrom(height: Int, options: WipeFiltersFromOptions?) = notReady()

    override fun close() {
        driver.close()
    }

    private fun notReady(): Nothing = throw NotImplementedError("storage repository not implemented yet")
}
```

Remove the stub `createSqliteDatabase` from `Types.kt`.

If SQLDelight names the query objects `keyValueQueries` / `utxoNamesQueries` differently, use the generated names.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.KeyValueTest --tests io.bluewallet.blueberry.storage.UtxoNamesTest --tests io.bluewallet.blueberry.storage.SchemaTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 4: Peers repository

**Files:**
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Peers.sq`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/PeersTest.kt`

**Interfaces:**
- Consumes: `PeerWrite`, `toSqliteServices`, `fromSqliteServices`, `currentTimeMillis`
- Produces: working `Database.peers`

- [ ] **Step 1: Write the failing peers tests**

Port helix3 `tests/unit/sqlite-peers.test.ts` (all tests except the helper-only first test, which already lives in `PeerServicesTest`). Use `ULong` literals (`2049uL`, `1uL shl 63`). `basePeer` default: host `1.2.3.4`, port `8333`, services `0uL`, alive `false`, usedForBlocks `false`, lastProbedAt `null`.

Required cases:

1. High service bit survives upsert and `listAliveWithServices` / `listWithServices`.
2. Upsert round-trip and count (`services = 2049uL`).
3. Conflict upsert refreshes services and does not clear `alive` / `lastProbedAt`; `usedForBlocks` in the write must not overwrite the stored flag (helix3 updates only `services` and `updated_at` on conflict).
4. Conflict upsert with `services = 0uL` keeps stored bits `64uL`.
5. `listAlive`, `markProbed`, `markAlive`, `markUsedForBlocks`.
6. `listAliveWithServices` with `AliveServiceOptions(unusedForBlocks = true)`.
7. `listWithServices` prefers alive; `listProbeQueue` orders never-probed first (hosts `3.3.3.3` then `4.4.4.4` for limit 2).

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.PeersTest`

Expected: FAIL (`peers` is `NotImplementedError`).

- [ ] **Step 3: Implement peers**

`Peers.sq` SQL must match helix3:

```sql
upsert:
INSERT INTO peers (
  host, port, services, alive, used_for_blocks,
  last_probed_at, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(host, port) DO UPDATE SET
  services = CASE
    WHEN excluded.services != 0 THEN excluded.services
    ELSE services
  END,
  updated_at = excluded.updated_at;

list:
SELECT * FROM peers ORDER BY host, port;

count:
SELECT COUNT(*) AS n FROM peers;

listAlive:
SELECT * FROM peers WHERE alive = 1 ORDER BY host, port;

listAliveWithServices:
SELECT * FROM peers
WHERE alive = 1 AND (services & ?) != 0
ORDER BY host, port
LIMIT ?;

listAliveWithServicesUnused:
SELECT * FROM peers
WHERE alive = 1
  AND used_for_blocks = 0
  AND (services & ?) != 0
ORDER BY host, port
LIMIT ?;

listWithServices:
SELECT * FROM peers
WHERE (services & ?) != 0
ORDER BY alive DESC,
  CASE WHEN last_probed_at IS NULL THEN 0 ELSE 1 END,
  last_probed_at ASC,
  host, port
LIMIT ?;

listProbeQueue:
SELECT * FROM peers
ORDER BY
  CASE WHEN last_probed_at IS NULL THEN 0 ELSE 1 END,
  last_probed_at ASC,
  CASE WHEN instr(host, ':') > 0 THEN 1 ELSE 0 END,
  host, port
LIMIT ?;

markProbed:
UPDATE peers SET last_probed_at = ?, updated_at = ? WHERE host = ? AND port = ?;

markAlive:
UPDATE peers SET alive = ?, updated_at = ? WHERE host = ? AND port = ?;

markUsedForBlocks:
UPDATE peers SET used_for_blocks = 1, updated_at = ? WHERE host = ? AND port = ?;
```

Map rows with `fromSqliteServices(row.services)`. Bind services with `toSqliteServices`. Bind `alive` / `used_for_blocks` as `0L`/`1L`. `upsert` uses `createdAt ?: currentTimeMillis()` and `updatedAt ?: currentTimeMillis()`. `listAliveWithServices` returns `emptyList()` when `limit <= 0`. Same for `listWithServices` and `listProbeQueue`.

Replace `peers` `notReady()` with this implementation.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.PeersTest --tests io.bluewallet.blueberry.storage.PeerServicesTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 5: Headers repository

**Files:**
- Create: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/HeaderWork.kt`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Headers.sq`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/HeadersTest.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/CheckpointFixtures.kt`

**Interfaces:**
- Consumes: `HeaderRecord`, `HeaderWrite`, bitcoin-headers `checkpointSeedRecord`, `CHECKPOINT_HEADER`, `equalBytes`
- Produces: working `Database.headers`

- [ ] **Step 1: Write fixtures and failing header tests**

`CheckpointFixtures.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import io.bluewallet.headers.CHECKPOINT_HEADER
import io.bluewallet.headers.checkpointSeedRecord

fun checkpointDbRecord(): HeaderRecord {
    val seed = checkpointSeedRecord()
    return HeaderRecord(
        height = seed.height.toInt(),
        hashInternalHex = seed.hashInternalHex,
        header = CHECKPOINT_HEADER.copyOf(),
    )
}

fun testHeader(height: Int, suffix: String, cumulativeWork: com.ionspin.kotlin.bignum.integer.BigInteger): HeaderWrite {
    return HeaderWrite(
        height = height,
        hashInternalHex = "i".repeat(64 - suffix.length) + suffix,
        header = ByteArray(80) { 0xab.toByte() },
        cumulativeWork = cumulativeWork,
    )
}
```

`HeadersTest.kt` cases (helix3 `sqlite-headers.test.ts`):

1. `ensureCheckpoint` seeds once. `count() == 1`. `tip()!!.height` equals `checkpointSeedRecord().height.toInt()`. `tip()!!.cumulativeWork > BigInteger.ZERO`. Second `ensureCheckpoint` is idempotent. A different `hashInternalHex` (`"00".repeat(32)`) throws. Message contains `checkpoint mismatch` and `Delete blueberry.data/blueberry.sqlite`.
2. `append` then `replaceAfter` keep caller-supplied `cumulativeWork`. After replace, `loadFrom(seed+1)` heights are `seed+1, seed+2, seed+3`. `heightForHashInternal` of the `b2` header is `seed+2`.
3. Empty `minHeight()` is `null`. After checkpoint, `minHeight()` is the seed height (this case is in helix3 `sqlite-filters.test.ts`; put it here).

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.HeadersTest`

Expected: FAIL (`headers` is `NotImplementedError`).

- [ ] **Step 3: Implement header work and headers**

`HeaderWork.kt`:

```kotlin
package io.bluewallet.blueberry.storage

import com.ionspin.kotlin.bignum.integer.BigInteger
import io.bluewallet.headers.MAINNET_POW_LIMIT
import io.bluewallet.headers.decodeBlockHeader
import io.bluewallet.headers.decodeCompactTarget
import io.bluewallet.headers.headerWork

internal fun headerWorkFromBytes(header: ByteArray): BigInteger {
    return try {
        val decoded = decodeBlockHeader(header)
        val target = decodeCompactTarget(decoded.bits, MAINNET_POW_LIMIT)
        headerWork(target)
    } catch (_: Exception) {
        BigInteger.ONE
    }
}
```

`Headers.sq`:

```sql
insert:
INSERT INTO headers (height, hash_internal_hex, header, cumulative_work)
VALUES (?, ?, ?, ?);

tip:
SELECT * FROM headers ORDER BY height DESC LIMIT 1;

count:
SELECT COUNT(*) AS n FROM headers;

minHeight:
SELECT MIN(height) AS h FROM headers;

get:
SELECT * FROM headers WHERE height = ?;

heightForHashInternal:
SELECT height FROM headers WHERE hash_internal_hex = ? LIMIT 1;

loadRange:
SELECT * FROM headers WHERE height >= ? AND height <= ? ORDER BY height ASC;

loadAll:
SELECT * FROM headers ORDER BY height ASC;

loadFrom:
SELECT * FROM headers WHERE height >= ? ORDER BY height ASC;

first:
SELECT * FROM headers ORDER BY height ASC LIMIT 1;

deleteAfter:
DELETE FROM headers WHERE height > ?;
```

`ensureCheckpoint`: if `count() == 0`, insert with `headerWorkFromBytes`. Else load `first`. If height, `hashInternalHex`, or header bytes differ (`equalBytes`), throw:

```
checkpoint mismatch: stored height $existing.height hashInternalHex $existing.hashInternalHex, expected height $checkpoint.height hashInternalHex $checkpoint.hashInternalHex. Delete blueberry.data/blueberry.sqlite (or clear headers rows) and restart.
```

`append` / `replaceAfter` run inside `transaction`. `insertWrites` uses caller `cumulativeWork` when set, else adds `headerWorkFromBytes`. Store work with `cumulative.toString()`. Parse with `BigInteger.parseString(row.cumulative_work)`.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.HeadersTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 6: Filter headers

**Files:**
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/FilterHeaders.sq`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/FilterHeadersTest.kt`

**Interfaces:**
- Consumes: `FilterHeaderRecord`, `hexToBytes`
- Produces: working `Database.filterHeaders`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry.storage

import io.bluewallet.headers.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FilterHeadersTest {
    @Test
    fun append_get_deleteFrom() {
        val db = createSqliteDatabase(":memory:")
        db.filterHeaders.append(
            listOf(
                FilterHeaderRecord(10, hexToBytes("aa".repeat(32))),
                FilterHeaderRecord(11, hexToBytes("bb".repeat(32))),
            ),
        )
        assertContentEquals(hexToBytes("aa".repeat(32)), db.filterHeaders.get(10)!!.header)
        assertEquals(11, db.filterHeaders.tip()!!.height)
        assertEquals(listOf(10, 11), db.filterHeaders.loadRange(10, 11).map { it.height })
        db.filterHeaders.deleteFrom(11)
        assertEquals(10, db.filterHeaders.tip()!!.height)
        db.close()
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.FilterHeadersTest`

Expected: FAIL.

- [ ] **Step 3: Implement filter headers**

```sql
insert:
INSERT INTO filter_headers (height, header) VALUES (?, ?);

tip:
SELECT * FROM filter_headers ORDER BY height DESC LIMIT 1;

get:
SELECT * FROM filter_headers WHERE height = ?;

minHeight:
SELECT MIN(height) AS h FROM filter_headers;

loadRange:
SELECT * FROM filter_headers
WHERE height >= ? AND height <= ?
ORDER BY height ASC;

deleteFrom:
DELETE FROM filter_headers WHERE height >= ?;
```

`append` no-ops on empty. Otherwise insert all rows in `transaction`.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.FilterHeadersTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 7: Filters repository and `wipeFiltersFrom`

**Files:**
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Filters.sq`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/FiltersTest.kt`

**Interfaces:**
- Consumes: `FilterRecord`, `HeightRange`, `WipeFiltersFromOptions`
- Produces: working `Database.filters` and `Database.wipeFiltersFrom`

- [ ] **Step 1: Write the failing filters tests**

Port every case in helix3 `tests/unit/sqlite-filters.test.ts` except:

- schema index (already in `SchemaTest`)
- `headers.minHeight` (already in `HeadersTest`)
- `filter headers append/get/deleteFrom` (already in `FilterHeadersTest`)
- `matched_blocks` / `blocks` cases (Task 8)

Keep: blob round-trip; `wipeFiltersFrom`; `missingRanges` (three tests); `completeInRange` (two tests); `firstHashMismatch` (use `checkpointDbRecord()`); `hashAt`; `listNeedingMatch` / `markScanned` / `countScanned`; markScanned queue test.

Also port `markUnscannedFrom` from helix3 `sqlite-key-value.test.ts`: append heights 1..5, `markScanned` all, `markUnscannedFrom(3)` yields needing `[3,4,5]`, `countScanned() == 2`, second call is idempotent.

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.FiltersTest`

Expected: FAIL.

- [ ] **Step 3: Implement filters and wipe**

`Filters.sq` statements (same SQL as helix3):

```sql
insert:
INSERT INTO filters (height, block_hash_internal_hex, filter) VALUES (?, ?, ?);

insertUnscanned:
INSERT OR IGNORE INTO filters_unscanned (height) VALUES (?);

deleteUnscannedOne:
DELETE FROM filters_unscanned WHERE height = ?;

deleteUnscannedRange:
DELETE FROM filters_unscanned WHERE height >= ? AND height <= ?;

listNeedingMatch:
SELECT f.height AS height,
       f.block_hash_internal_hex AS block_hash_internal_hex,
       f.filter AS filter
FROM filters_unscanned u
INNER JOIN filters f ON f.height = u.height
ORDER BY u.height ASC
LIMIT ?;

countFilters:
SELECT COUNT(*) AS n FROM filters;

countUnscanned:
SELECT COUNT(*) AS n FROM filters_unscanned;

hashAt:
SELECT block_hash_internal_hex AS h FROM filters WHERE height = ?;

countInRange:
SELECT COUNT(*) AS n FROM filters WHERE height >= ? AND height <= ?;

minHeight:
SELECT MIN(height) AS h FROM filters;

maxHeight:
SELECT MAX(height) AS h FROM filters;

has:
SELECT 1 AS ok FROM filters WHERE height = ? LIMIT 1;

get:
SELECT height, block_hash_internal_hex, filter FROM filters WHERE height = ?;

firstHashMismatch:
SELECT f.height AS height
FROM filters f
INNER JOIN headers h ON h.height = f.height
WHERE f.height >= ? AND f.height <= ?
  AND f.block_hash_internal_hex != h.hash_internal_hex
ORDER BY f.height ASC
LIMIT 1;

heightsInRange:
SELECT height FROM filters WHERE height >= ? AND height <= ? ORDER BY height ASC;

markUnscannedFrom:
INSERT OR IGNORE INTO filters_unscanned (height)
SELECT height FROM filters WHERE height >= ?;

deleteUnscannedFrom:
DELETE FROM filters_unscanned WHERE height >= ?;

deleteFiltersFrom:
DELETE FROM filters WHERE height >= ?;

deleteFilterHeadersFrom:
DELETE FROM filter_headers WHERE height >= ?;

deleteFilterHeaderAt:
DELETE FROM filter_headers WHERE height = ?;
```

`append` inserts each filter and `INSERT OR IGNORE` into `filters_unscanned` in one transaction.

`countScanned` is `count(filters) - count(filters_unscanned)`.

`markScanned`: sort heights. If contiguous, one range delete. Else delete each height in a transaction.

`markUnscanned(heights)`: `INSERT OR IGNORE` each height into `filters_unscanned` in one transaction. Empty list is a no-op.

`missingRanges` and `completeInRange` copy helix3 logic:

- If `to < from`, missing is `[]`, complete is `true`.
- Chunk gaps with `max(1, maxSpan)`.
- If the table is empty, chunk the whole `[from, to]`.
- If `maxH - minH + 1 == count`, only emit leading/trailing gaps (do not walk rows).
- Else walk present heights in `[from, to]` and emit holes.
- `completeInRange`: false when empty or when `minH > from` or `maxH < to`. If the full table is contiguous, true. Else `countInRange == to - from + 1`.

Optional count caches from helix3 are allowed. If you skip caches, use SQL counts. Behaviour must match the tests.

`wipeFiltersFrom(height, options)` in one `transaction`: delete unscanned `>= height`, filters `>= height`, filter headers `>= height`. If `options?.prevHeaderHeight != null`, also delete that exact filter-header row.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.FiltersTest --tests io.bluewallet.blueberry.storage.FilterHeadersTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 8: Matched blocks, blocks, parsed blocks, transactions

**Files:**
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/MatchedBlocks.sq`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Blocks.sq`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/ParsedBlocks.sq`
- Create: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Transactions.sq`
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/BlocksAndTxsTest.kt`

**Interfaces:**
- Consumes: `MatchedBlock`, `DownloadedBlock`, `StoredTx`, `TxSetFingerprint`
- Produces: working `matchedBlocks`, `blocks`, `parsedBlocks`, `transactions`

- [ ] **Step 1: Write the failing tests**

Port helix3 `sqlite-filters.test.ts` matched/blocks cases and all of `sqlite-parsed-txs.test.ts`, plus `transactions.minHeight()` from `sqlite-key-value.test.ts`.

Cases:

1. `matchedBlocks.insert` is idempotent (`true` then `false`; count stays 1).
2. `listNeedingDownload` after three matches; `blocks.insert` first height `true`, second insert `false`; `get` returns first blob; needing download becomes `[11, 12]` then limit 1 is `[11]`.
3. Parse queue: three blocks, `listNeedingParse` is `[10,11,12]`; `mark(11)` twice; count 1; needing `[10,12]`.
4. Tx upsert replace, `setNetDelta`, `list()` newest-first (`height DESC, tx_index DESC`), `fingerprint` `{count=2, netDeltaSum=92, newestTxid=a*64}`, `get` blob / null.
5. Fresh `fingerprint` is `{0, 0, null}`.
6. After txs at heights 10 and 4, `minHeight() == 4`.

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.BlocksAndTxsTest`

Expected: FAIL.

- [ ] **Step 3: Implement the four repositories**

Matched:

```sql
insert:
INSERT OR IGNORE INTO matched_blocks (height, block_hash_internal_hex) VALUES (?, ?);

get:
SELECT height, block_hash_internal_hex FROM matched_blocks WHERE height = ?;

count:
SELECT COUNT(*) AS n FROM matched_blocks;

listNeedingDownload:
SELECT m.height AS height, m.block_hash_internal_hex AS block_hash_internal_hex
FROM matched_blocks m
LEFT JOIN blocks b ON b.height = m.height
WHERE b.height IS NULL
ORDER BY m.height ASC
LIMIT ?;
```

`insert` returns true when SQLite `changes()` is `> 0`. Add `SELECT changes();` as `changes:` and call it immediately after insert. Same for `blocks.insert`.

Blocks:

```sql
insert:
INSERT OR IGNORE INTO blocks (height, block_hash_internal_hex, block) VALUES (?, ?, ?);

count:
SELECT COUNT(*) AS n FROM blocks;

has:
SELECT 1 AS ok FROM blocks WHERE height = ?;

get:
SELECT height, block_hash_internal_hex, block FROM blocks WHERE height = ?;

listNeedingParse:
SELECT b.height AS height, b.block_hash_internal_hex AS block_hash_internal_hex, b.block AS block
FROM blocks b
LEFT JOIN parsed_blocks p ON p.height = b.height
WHERE p.height IS NULL
ORDER BY b.height ASC
LIMIT ?;
```

Parsed:

```sql
has:
SELECT 1 AS ok FROM parsed_blocks WHERE height = ? LIMIT 1;

mark:
INSERT OR IGNORE INTO parsed_blocks (height) VALUES (?);

count:
SELECT COUNT(*) AS n FROM parsed_blocks;

clearFrom:
DELETE FROM parsed_blocks WHERE height >= ?;
```

Transactions:

```sql
upsert:
INSERT INTO transactions (
  txid, height, tx_index, block_hash_internal_hex, tx, net_delta_sats
) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(txid) DO UPDATE SET
  height = excluded.height,
  tx_index = excluded.tx_index,
  block_hash_internal_hex = excluded.block_hash_internal_hex,
  tx = excluded.tx,
  net_delta_sats = excluded.net_delta_sats;

list:
SELECT txid, height, tx_index, block_hash_internal_hex, tx, net_delta_sats
FROM transactions
ORDER BY height DESC, tx_index DESC;

count:
SELECT COUNT(*) AS n FROM transactions;

fingerprint:
SELECT
  COUNT(*) AS n,
  COALESCE(SUM(net_delta_sats), 0) AS s,
  (SELECT txid FROM transactions ORDER BY height DESC, tx_index DESC LIMIT 1) AS newest
FROM transactions;

minHeight:
SELECT MIN(height) AS h FROM transactions;

get:
SELECT txid, height, tx_index, block_hash_internal_hex, tx, net_delta_sats
FROM transactions WHERE txid = ?;

setNetDelta:
UPDATE transactions SET net_delta_sats = ? WHERE txid = ?;
```

Limits `<= 0` return empty lists.

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.BlocksAndTxsTest`

Expected: PASS.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 9: `rewindAfter` and nested transactions

**Files:**
- Modify: `storage/src/commonMain/sqldelight/io/bluewallet/blueberry/storage/Filters.sq` (add rewind deletes if missing)
- Modify: `storage/src/commonMain/kotlin/io/bluewallet/blueberry/storage/SqliteDatabase.kt`
- Test: `storage/src/commonTest/kotlin/io/bluewallet/blueberry/storage/RewindTest.kt`

**Interfaces:**
- Consumes: `rewindAfter`, `transaction`, `headers.replaceAfter`
- Produces: working `rewindAfter`; nested `transaction` already in Task 3 must stay

- [ ] **Step 1: Write the failing rewind test**

Port helix3 `sqlite-rewind.test.ts`:

1. `ensureCheckpoint(checkpointDbRecord())`.
2. Append two placeholder headers at `seed+1` and `seed+2` with work `base+1`, `base+2`.
3. Filter headers, filters, matched block, block, parsed mark, and a tx at `h2`.
4. Inside `db.transaction { rewindAfter(h1); headers.replaceAfter(h1, [header h2 suffix b2 work base+20]) }`.
5. Assert tip hash ends with `b2`; filter header/filter at `h2` gone; filter at `h1` remains; matched count 0; `blocks.has(h2)` false; `parsedBlocks.has(h2)` false; `transactions.list()` empty.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :storage:jvmTest --tests io.bluewallet.blueberry.storage.RewindTest`

Expected: FAIL (`rewindAfter` still `NotImplementedError`).

- [ ] **Step 3: Implement rewind**

`rewindAfter` runs in `transaction` and deletes `height > ancestorHeight` from: `filter_headers`, `filters_unscanned`, `filters`, `matched_blocks`, `blocks`, `parsed_blocks`, `transactions`. It does not delete `headers`.

Add these statements if they are not already present:

```sql
rewindFilterHeaders:
DELETE FROM filter_headers WHERE height > ?;

rewindUnscanned:
DELETE FROM filters_unscanned WHERE height > ?;

rewindFilters:
DELETE FROM filters WHERE height > ?;

rewindMatched:
DELETE FROM matched_blocks WHERE height > ?;

rewindBlocks:
DELETE FROM blocks WHERE height > ?;

rewindParsed:
DELETE FROM parsed_blocks WHERE height > ?;

rewindTransactions:
DELETE FROM transactions WHERE height > ?;
```

If you cache filter counts, set those caches to `null` after rewind and wipe.

- [ ] **Step 4: Run the full storage suite**

Run: `./gradlew :storage:jvmTest`

Expected: all `:storage` tests PASS. No `NotImplementedError` remains on `Database`.

- [ ] **Step 5: Commit only if the user asks**

---

### Task 10: Wire `:shared` to `:storage`

**Files:**
- Modify: `shared/build.gradle.kts`
- Test: existing `shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt` (do not change assertions)

**Interfaces:**
- Consumes: project `:storage`
- Produces: `:shared` compiles with `:storage` on the classpath. Click me text stays the same.

- [ ] **Step 1: Run shared tests (baseline)**

Run: `./gradlew :shared:jvmTest`

Expected: PASS (current vendor tests).

- [ ] **Step 2: Depend on storage and link iOS sqlite**

In `shared/build.gradle.kts` `commonMain.dependencies` add:

```kotlin
implementation(project(":storage"))
```

In the iOS framework block add:

```kotlin
linkerOpts("-lsqlite3")
```

Do not change `App.kt` or `VendorLibraryStatus.kt`.

- [ ] **Step 3: Run shared and storage tests**

Run: `./gradlew :storage:jvmTest :shared:jvmTest`

Expected: PASS. Click me lines stay the five vendor lines already on master.

- [ ] **Step 4: Commit only if the user asks**
