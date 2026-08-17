# KMP storage module

Date: 2026-08-17  
Status: approved (conversation)

## Goal

Add a `:storage` Kotlin Multiplatform module. It persists Blueberry data in SQLite.

The SQLite schema must match helix3 `src/db/schema.ts`. The public Kotlin API must match helix3 `src/db/types.ts` and `createSqliteDatabase`.

## Non-goals

- Open a `.sqlite` file written by helix3
- Make files portable between helix3 and Blueberry.kmp
- Change Click me, Compose UI, or vendor status
- Port sync, wallet, peers, or filters download
- Room, raw `androidx.sqlite` without SQLDelight, or a bundled SQLite driver on all platforms
- helix3 desktop PRAGMAs `cache_size`, `mmap_size`, and `SQLITE_FCNTL_PERSIST_WAL`

## Stack

- New Gradle module `:storage`
- Targets: Android, iOS (`iosArm64`, `iosSimulatorArm64`), desktop JVM (same as `:shared`)
- SQLDelight 2.3.2
- Drivers: `android-driver`, `native-driver`, `sqlite-driver` (JDBC)
- `:storage` depends on `io.bluewallet:bitcoin-headers`
- `:shared` depends on `:storage`

Package: `io.bluewallet.blueberry.storage`

## Schema

Copy the helix3 `CREATE TABLE` and `CREATE INDEX` statements.

Tables: `peers`, `headers`, `filter_headers`, `filters`, `filters_unscanned`, `matched_blocks`, `blocks`, `parsed_blocks`, `transactions`, `key_value`, `utxo_names`.

Indexes: `headers_hash_internal_hex`, `filters_height_hash`, `peers_alive_used`.

The SQLite schema that SQLDelight emits must match helix3 `schema.ts` after whitespace normalize. SQLDelight `AS` type adapters in `.sq` files are allowed only if they do not change that SQLite text.

Callers do not use the generated SQLDelight class. Name that class `StorageDb` so it does not clash with the helix3 `Database` type.

Apply schema so a second open of the same Blueberry.kmp file succeeds. Do not call a bare `CREATE TABLE` that fails when tables already exist.

## Public API

`createSqliteDatabase(path: String): Database`

- `path == ":memory:"` opens an in-memory database.
- Any other `path` opens that file path on all targets, including Android.
- After open: apply schema, then set `PRAGMA journal_mode = WAL`, `PRAGMA synchronous = NORMAL`, `PRAGMA wal_autocheckpoint = 10000`.

Repository method behaviour matches helix3 `src/db/sqlite-database.ts` (SQL, side effects, and return values). `types.ts` is the Kotlin surface. `sqlite-database.ts` is the behaviour source.

`Database` has the same repositories and methods as helix3 `types.ts`:

- `peers`, `headers`, `filterHeaders`, `filters`, `matchedBlocks`, `blocks`, `parsedBlocks`, `transactions`, `keyValue`, `utxoNames`
- `transaction(fn)`, `rewindAfter(ancestorHeight)`, `wipeFiltersFrom(height, options)`, `close()`

Kotlin types map helix3 types as follows:

| helix3 | Kotlin |
| --- | --- |
| `boolean` | `Boolean` (SQLite `INTEGER` 0/1) |
| `number` heights, ports, indexes | `Int` |
| `number` timestamps, `netDeltaSats` | `Long` |
| `Uint8Array` | `ByteArray` |
| `bigint` peer `services` | `ULong` (unsigned nServices) |
| `bigint` `cumulativeWork` | `com.ionspin.kotlin.bignum.integer.BigInteger` (same as bitcoin-headers) |

`PeerServices` copies helix3 `peer-services.ts`: unsigned nServices ↔ signed SQLite `INTEGER` bit pattern. Store and filter through that encode. A high bit (`1 shl 63`) must survive upsert and service-bit queries.

`HeadersRepository` uses bitcoin-headers for work when the caller omits `cumulativeWork`. If header bytes are not a real header, work is `1` (placeholder headers in tests).

`headers.ensureCheckpoint` is idempotent when the stored row matches. A hash mismatch throws. The message contains `checkpoint mismatch` and `Delete blueberry.data/blueberry.sqlite` (same as helix3).

`transaction` runs one SQLite transaction. Nested calls join the open transaction.

`rewindAfter` deletes rows with `height > ancestorHeight` from `filter_headers`, `filters_unscanned`, `filters`, `matched_blocks`, `blocks`, `parsed_blocks`, and `transactions`. It does not delete `headers`. Header rewrite stays on `headers.replaceAfter`.

`wipeFiltersFrom` deletes `filters_unscanned`, `filters`, and `filter_headers` with `height >= height`. If `prevHeaderHeight` is set, also delete that exact `filter_headers` row.

SQLite errors throw. After `close()`, repository calls fail.

## Units

| Unit | Role |
| --- | --- |
| `.sq` schema + queries | SQLDelight SQL. Table SQL matches helix3. |
| `Database` + repository interfaces | Public API. Callers depend on this only. |
| `createSqliteDatabase` | Open driver, apply schema and PRAGMAs, return `Database`. |
| Repository wrappers | Map `StorageDb` rows to public types. |
| `PeerServices` | nServices encode/decode. |
| Header work helper | bitcoin-headers; work `1` on decode failure. |

## Testing

`commonTest` in `:storage`. Tests call `createSqliteDatabase(":memory:")` and the public `Database` API.

Port helix3 tests:

- `sqlite-key-value`
- `sqlite-utxo-names`
- `sqlite-peers`
- `sqlite-headers`
- `sqlite-filters`
- `sqlite-parsed-txs`
- `sqlite-rewind`

Add a schema test: table names, columns, and indexes match helix3.

Do not add UI tests. Do not change Click me.

## Out of scope for this change

Desktop and iOS apps do not need a new storage UI. iOS must link `sqlite3` when an app target first uses `:storage`.
