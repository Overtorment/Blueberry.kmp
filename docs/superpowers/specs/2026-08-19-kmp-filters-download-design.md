# KMP filters download

Date: 2026-08-19  
Status: approved (conversation; remaining section approvals skipped at user request)

## Goal

Port helix3 compact-filter download into Blueberry.kmp so it authenticates BIP-157 filter headers, fetches and verifies basic cfilters from wallet birthday to the header tip, persists through `:storage`, and drives a Filters DL tile plus `filt` socket counts on the Start screen.

Download starts only after the onboarding gate is `Start`, next to peers-discovery and chain-headers.

## Non-goals

- File logging (`initFileLog`, `--log`, log-line unit assertions against `[filters-download]`)
- helix3 TUI chrome (Panel, magenta box, key hints)
- Filters matching, blocks, parse, broadcast, or the sync-idle *module* (idle/catchup *listeners* stay; nothing emits them yet)
- Extracting a `:net` Gradle module
- Process re-exec after onboarding
- Opening a helix3 `.sqlite` file
- Changing `:storage`, `:bus`, or `:wallet` public APIs
- Compose UI tests
- Live mainnet in default Gradle test tasks (the running app does use live mainnet)
- Changing Kotlin `2.4.10`

## Decisions

| Topic | Choice |
| --- | --- |
| Module split | `filter-sync` + `filter-session-pool` + filter config live in `:peers/net`. New `:filters` holds `createFiltersDownloadModule`. `:shared` holds progress store, hydrate, Filters DL UI, runtime wiring. |
| UI | Filters DL tile + `filt` sockets on the existing Start screen (helix3). |
| Behaviour source | Current helix3 `filters-download.ts` / `filter-sync.ts` / `filter-session-pool.ts` (birthday range, concurrency 10, batch 100, `markAlive`, `wipeFiltersFrom`, persist-batch, `sync:idle` quiet), not the 2026-08-01 spec. |
| Settings | Opening Settings does not stop filters, headers, or discovery. |
| Logging | `log` / `logError` call sites stay. Implementation is a no-op, same as `:peers`. Injected `log` test seams stay. |
| Tests | Injected `openSession`. No live `:8333` in CI. Drop file-log text assertions. Keep injected-`log` diagnostics. |

## Stack

- New Gradle module `:filters`
- Targets: Android, iOS (`iosArm64`, `iosSimulatorArm64`), desktop JVM (same as `:headers`)
- Package: `io.bluewallet.blueberry.filters` with subpackage `modules`
- `:filters` depends on `:peers` (api: `Module`, `PlatformNet`, filter-sync types), `:storage`, `:bus`, `:wallet` (birthday), `io.bluewallet:bip157`, `io.bluewallet:bip324`, `kotlinx-coroutines-core`
- `:shared` depends on `:filters`
- Filter-sync types live in `io.bluewallet.blueberry.peers.net`
- Do not change Kotlin `2.4.10`

## helix3 map

| helix3 | Kotlin |
| --- | --- |
| `config.filterSyncTimeoutMs` / `filterConcurrency` / `filterHeaderBatchSize` / `filterBatchSize` | `:peers` `Config` |
| `src/net/filter-sync.ts` | `:peers` `net/FilterSync.kt` |
| `src/net/filter-session-pool.ts` | `:peers` `net/FilterSessionPool.kt` |
| `src/modules/filters-download.ts` | `:filters` `modules/FiltersDownload.kt` |
| `src/tui/filters-progress-store.ts` + `progress-eta.ts` | `:shared` `FiltersProgressStore.kt` (reuse existing ETA helpers) |
| `hydrateFilters` | `:shared` next to `hydrateHeaders` |
| `FiltersDownload.tsx` copy | `:shared` Filters DL block on Start screen |
| `startApp` filters slice | expand `PeersRuntime` |

Reuse `:peers` `Module` / `ModuleContext` / `detachLoop` / `PlatformNet`. Do not re-port those types.

## Units

| Unit | Job | Depends on |
| --- | --- | --- |
| Config filter fields | timeouts + batch sizes | none |
| `openFilterSession` | BIP-324 handshake + BIP-157 getcfcheckpt / getcfheaders / getcfilters | `PlatformNet`, bip324, bip157 |
| `createInactivityTimeout` / `runWithInactivityTimeout` | refreshable cfilter inactivity budget | coroutines |
| `FilterSessionPool` | reuse sessions, cool on fail, emit `filt` open count | `openFilterSession` |
| `createFiltersDownloadModule` | birthday range, reorg wipe, sequential cfheaders, parallel cfilters, persist, emit | pool or injected openSession, wallet birthday |
| `FiltersProgressStore` | percent, ETA, downloaded/total | none |
| `hydrateFilters` | `filters.count()` + durable total | store, `:storage` |
| Start screen Filters DL | bar, counts, ETA | store |
| `PeersRuntime` | Start/stop discovery + headers + filters after gate `Start` | `:peers`, `:headers`, `:filters` |

## Public API

Names match helix3 exports. Promises are `suspend`. `AbortSignal` is coroutine cancellation. Storage heights stay `Int`. `bigint` services → `ULong`. `NODE_COMPACT_FILTERS` stays the bip157 `Int`; convert with `.toULong()` at the storage boundary.

`ok: true/false` results become sealed `Ok` / `Err`.

### `:peers` Config

```kotlin
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
```

`persistBatchSize` is a module option default (`25`), not a Config field.

### Filter sync (`:peers` `net/FilterSync.kt`)

```kotlin
sealed class FilterBatchResult<out T> {
    data class Ok<T>(val value: T) : FilterBatchResult<T>()
    data class Err(val error: String) : FilterBatchResult<Nothing>()
}

data class CFHeadersResult(
    val filterType: Int,
    val stopHash: ByteArray,
    val previousFilterHeader: ByteArray,
    val filterHashes: List<ByteArray>,
)

data class CFilterItem(val blockHash: ByteArray, val filterBytes: ByteArray)

interface FilterSessionApi {
    val services: ULong
    suspend fun getCFCheckpt(stopHash: ByteArray): List<ByteArray>
    suspend fun getCFHeaders(startHeight: Int, stopHash: ByteArray): CFHeadersResult
    suspend fun getCFilters(
        startHeight: Int,
        stopHash: ByteArray,
        expectCount: Int,
        onFilter: (suspend (CFilterItem) -> Unit)? = null,
    ): List<CFilterItem>
    suspend fun close()
}

class FilterSyncOptions(
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val connect: TcpConnect,
    val runSession: (suspend (ByteDuplex, Int) -> FilterSessionApi)? = null,
)

suspend fun openFilterSession(
    host: String,
    port: Int,
    options: FilterSyncOptions,
): FilterBatchResult<FilterSessionApi>
```

Production session: BIP-324 `Protocol.connect` + `completeVersionHandshake` (`APP_NAME` / `APP_VERSION`). BIP-157 outbound via `encodeOutbound` written as `Message.Opaque(WireMessageType.Short(shortId), payload)`. Inbound: read until matching short id (`cfcheckpt` / `cfheaders` / `cfilter`); `answerPing` for everything else.

`getCFCheckpt` / `getCFHeaders` use a hard timeout (`filterSyncTimeoutMs`). `getCFilters` uses a refreshable inactivity timeout: each received cfilter resets the budget. Export `createInactivityTimeout` and `runWithInactivityTimeout` so the helix3 inactivity tests port.

Connect timeout closes the duplex if handshake never finishes (same `connectOrAbort` idea as header-sync / peer-probe).

### Filter session pool (`:peers` `net/FilterSessionPool.kt`)

```kotlin
data class FilterPoolPeer(val host: String, val port: Int)

class FilterSessionPoolOptions(
    val connect: TcpConnect,
    val openSession: (suspend (String, Int, FilterSyncOptions) -> FilterBatchResult<FilterSessionApi>)? = null,
    val max: Int? = null,
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val coolMs: Long? = null,
    val now: (() -> Long)? = null,
    val onOpenCount: ((Int) -> Unit)? = null,
    val onDiagnostic: ((String) -> Unit)? = null,
)

interface FilterSessionPool {
    fun setPeers(peers: List<FilterPoolPeer>)
    suspend fun <T> withSession(
        fn: suspend (FilterSessionApi, FilterPoolPeer) -> T,
    ): T?
    fun coolDelayMs(): Long
    suspend fun closeAll()
}

fun createFilterSessionPool(options: FilterSessionPoolOptions): FilterSessionPool
```

Defaults: `max = filterConcurrency`, `connectTimeoutMs = peerProbeTimeoutMs`, `syncTimeoutMs = filterSyncTimeoutMs`, `coolMs = 30_000`.

Behaviour matches helix3: reuse idle sessions; cool a failed endpoint; `withSession` returns `null` when no peer can be leased; `closeAll` bumps a generation so an in-flight open is closed and the lease returns `null`; `onOpenCount` fires when the open FD count changes (busy or live session).

### Module (`:filters` `modules/FiltersDownload.kt`)

```kotlin
class FiltersDownloadOptions(
    val net: PlatformNet,
    val openSession: (suspend (String, Int, FilterSyncOptions) -> FilterBatchResult<FilterSessionApi>)? = null,
    val connectTimeoutMs: Long? = null,
    val syncTimeoutMs: Long? = null,
    val concurrency: Int? = null,
    val filterBatchSize: Int? = null,
    val headerBatchSize: Int? = null,
    val persistBatchSize: Int? = null,
    val idleDelayMs: Long? = null,
    val coolMs: Long? = null,
    val now: (() -> Long)? = null,
    val onDownloadRun: (() -> Unit)? = null,
    val log: ((String) -> Unit)? = null,
)

fun createFiltersDownloadModule(
    ctx: ModuleContext,
    options: FiltersDownloadOptions,
): Module
```

Module name: `filters-download`.

Defaults from Config / helix3: `headerBatchSize` capped at `MAX_GETCFHEADERS_RANGE`, `filterBatchSize` capped at `MAX_GETCFILTERS_RANGE`, `persistBatchSize = 25`, `idleDelayMs = 250`, `coolMs = 30_000`.

### Progress store (`:shared`)

```kotlin
data class FiltersProgress(
    val downloaded: Int = 0,
    val total: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 0,
)

interface FiltersProgressStore {
    fun get(): FiltersProgress
    fun applyEvent(at: Long, downloaded: Int, total: Int)
    fun subscribe(listener: () -> Unit): () -> Unit
}

fun createFiltersProgressStore(): FiltersProgressStore
fun hydrateFilters(
    db: Database,
    store: FiltersProgressStore,
    rangeTotal: Int? = null,
    at: Long = currentTimeMillis(),
)
```

Store / ETA match helix3 `filters-progress-store.ts` (max 8 advancing samples; reset on regress or leave-complete; done → ETA 0). Reuse the same ETA math as `HeadersProgressStore` (do not invent a second algorithm).

`hydrateFilters`: `stored = db.filters.count()`. `total = sessionOrDurableTotal(rangeTotal, store.total, stored)` — incoming `> 0` wins, else previous store total if `> 0`, else stored. `downloaded = if (total > 0) min(stored, total) else stored`. On `filters:progress`, call `hydrateFilters(db, store, p.total, p.at)` so `total == 0` does not clobber a DB seed.

## Data flow

```
gate Start
  → PeersRuntime.start()
      hydrate peers + headers + filters
      bind sockets / headers:progress / filters:progress
      start peers-discovery
      start chain-headers
      start filters-download
  → filters-download
      headers:progress / peers:updated → kick
      sync:idle → quiet (ignore peers:updated)
      sync:catchup → unquiet + run
      run:
        pending birthday → emit 0/0, wait
        no headers or compactFilterFrom null → emit 0/0, stop run
        tip < filterFrom → wait
        chainFrom = birthday ok
          ? max(minH, floor(filterFrom / 1000) * 1000)
          : minH
        reconcileReorg (wipeFiltersFrom + firstHashMismatch)
        emit progress on filterFrom…tip (haveCached = filters.count())
        refresh CF peers (alive 512, else stored 256)
        phase 1: sequential getcfcheckpt + getcfheaders, verify, append filter_headers
        phase 2: missingRanges queue, up to concurrency workers, getcfilters, verify, persist
        completeInRange → closeAll, idle
  → bus filters:progress + peers:sockets kind=filt
  → FiltersProgressStore + PeerSocketsStore
  → Start screen
```

## Loop (current helix3)

Port `filters-download.ts` 1:1.

**Peers.** `listAliveWithServices(NODE_COMPACT_FILTERS.toULong(), 512)`. If empty, `listWithServices(..., 256)`. `pool.setPeers`. Success paths call `peers.markAlive(host, port, true)`.

**Range.** `filterFrom = compactFilterFrom(db)`. Filter *headers* start at `chainFrom` (checkpoint-aligned floor of birthday when birthday is `ok`; else header min). Filter *bodies* download `[filterFrom, tip]`. Progress `total = tip - filterFrom + 1`. `downloaded = min(haveCached, total)` where `haveCached = filters.count()` (O(1); do not use max-height span).

**Reorg.** Before each fetch: if filter-header tip or filter maxHeight `> headers.tip`, `wipeFiltersFrom(tip+1)`. Then `firstHashMismatch` from `max(chainFrom, hashCheckedThrough+1)` through `min(maxFilter, tip)`; wipe from the mismatch. `wipeFiltersFrom` uses `{ prevHeaderHeight = rangeFrom - 1 }` when wiping exactly at `rangeFrom` and `rangeFrom > 0`.

**Phase 1 — filter headers (sequential).** `firstMissingFilterHeader` via tip. First bootstrap batch (when `from` is not a BIP-157 checkpoint height) extends through the next checkpoint (`CF_CHECKPT_INTERVAL = 1000`), capped by `MAX_GETCFHEADERS_RANGE`. Cache `getCFCheckpt(tip hash)` per tip. Verify with `deriveFilterHeaders` against in-range checkpoints. Height 0 is not a BIP-157 checkpoint — genesis first batch must contain checkpoint 1000. Persist `previousFilterHeader` at `from-1` when `from > 0` and that row is missing; reject if it mismatches an existing row. Discard a batch if the stop header hash changed in flight.

**Phase 2 — filters (parallel).** `filters.missingRanges(filterFrom, tip, filterBatchSize)`. Up to `min(concurrency, queue.length)` workers. Stream `onFilter` verifies with `verifyCFilterAgainstHeader` (prev at height 0 is 32 zero bytes). Persist every `persistBatchSize` verified new rows; drop rows whose block hash is no longer canonical. On incomplete-batch failure, flush verified rows first. Re-queue remaining `missingRanges` for that span for up to 8 failures, then drop. `withSession == null` re-queues the range and waits `min(1000, coolDelayMs() || 50)`.

**Busy / dirty.** `requestRun` while busy sets `needsRun` and kicks waiters; after the run finishes, if `needsRun` start again. Idle + `headers:progress` starts a new run.

**Quiet.** `sync:idle` sets quiet. `sync:catchup` clears quiet and `requestRun("peers")`. `peers:updated` while quiet does not start a new run (still kicks waiters).

**Stop.** Synchronous `Module.stop`. Unsubscribe, kick waiters, `pool.closeAll()`, join the loop with `runBlocking`. Do not cancel the in-flight run first — `stop()` must wait for it.

**UI emit.** Cap bus spam at 100ms except forced emits (run start, phase complete).

`log` / `logError` call sites stay (no-op). Injected `options.log` is the diagnostic seam. Do not assert `[filters-download]` file-log text.

## App lifecycle

`PeersRuntime` also creates `FiltersProgressStore`, binds `filters:progress` → hydrate, hydrates filters from DB **before** starting modules, then starts `filters-download` after chain-headers `start()`.

```kotlin
createFiltersDownloadModule(
    ModuleContext(bus, db),
    FiltersDownloadOptions(net = net),
)
```

`start()` throw for any module: emit `module:status` error for that module name; keep the Start screen.

`stop()`: stop filters, then headers, then discovery, then unbind. Opening Settings does not stop.

## Screens

Material3. Existing `MaterialTheme`. No TUI Panel chrome.

**Start** (existing Peers screen, plus Chain tip, plus Filters DL):

1. Title `Peers`
2. `formatPeerSockets(counts)` (already includes `filt`)
3. `{known} known`
4. Title `Chain tip`
5. `progressBar(percent, 10)` / `{downloaded}/{total}` / `{height} tip` / ETA while `< 100`
6. Title `Filters DL`
7. `progressBar(percent, 10)`
8. `{downloaded}/{total}`
9. `ETA {formatEta(etaMs)}` only while `percent < 100`
10. Button `Settings`

## Errors

| Case | Behavior |
| --- | --- |
| Connect/handshake throw | `FilterBatchResult.Err`; duplex closed |
| Session open fail | cool that peer; `withSession` returns `null` |
| Timeout | `Err` containing timed out / inactive; duplex closed |
| cfheaders verify fail / missing checkpoint | do not persist; throw; pool retires session |
| cfilter verify fail / duplicate / out of range | throw; pool retires session; range re-queued |
| Reorg during in-flight batch | drop stale rows; do not persist replaced hashes |
| No CF peers | wait on kick (`idleDelayMs`) |
| Pending birthday | emit `0/0`; wait |
| Loop throw | `detachLoop` → `module:status` error |
| `module.start()` throw in App | `module:status` error; Start screen stays |

## Testing

TDD. Port helix3 unit tests. Default Gradle tests never call live mainnet TCP.

`:peers` `commonTest` (add):

- `filter-sync.test.ts` → `FilterSyncTest` (connect fail → Err, injected `runSession`, inactivity refresh, activity outlives timeout)
- `filter-session-pool.test.ts` → `FilterSessionPoolTest` (reuse, cool + next peer, onOpenCount, coolMs with one peer, closeAll during open)

`:filters` `commonTest`:

- `filters-download.test.ts` → `FiltersDownloadTest` (all behaviour tests). Drop assertions that only check file-log text. Keep injected-`log` diagnostics, waiting/append/progress/reorg/retry/birthday/idle/stop-join.

`:shared` `commonTest`:

- `filters-progress-store.test.ts` → percent/ETA/non-advancing/resume-after-complete
- `hydrateFilters` + bus: DB seed, `total: 0` does not clobber, `total > 0` updates total
- Runtime bind: hydrate then progress event

Use `waitFor` polling. Tests use `:memory:` SQLite and injected `openSession`. Easy-difficulty / unique-hash header fixtures and `buildFilterChain` (`bip157`/`bip158` `filterHash` + `filterHeader`) port helix3 helpers. `CF_CHECKPT_INTERVAL` fixtures at 998–1000 and genesis 0–1000 stay.

Pass:

- `./gradlew :peers:jvmTest :peers:testAndroidHostTest`
- `./gradlew :filters:jvmTest :filters:testAndroidHostTest`
- `./gradlew :shared:jvmTest :shared:testAndroidHostTest`
- `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :filters:compileKotlinJvm :shared:compileKotlinJvm`

iOS simulator tests stay optional on Linux.

Manual check after implement: desktop or Android, onboarded wallet. Start screen shows Filters DL hydrating from `filters.count()` (`0/0` or `N/N` after restart), then rising `downloaded/total` and `filt` while compact-filter peers exist. Settings still opens; sync keeps running.

## Success

- Gate `Start` starts filters-download after chain-headers
- Filter headers authenticate via `cfcheckpt` + `cfheaders`; cfilters verify before persist
- Download range is birthday → tip (not hardcoded 550000)
- Alive (else stored) `NODE_COMPACT_FILTERS` peers are used; success marks alive
- Reorg uses `wipeFiltersFrom` + hash mismatch
- Start screen shows Filters DL bar, counts, ETA; Peers line shows `filt`
- Helix3 behaviour tests (minus file-log assertions) pass on JVM and Android host
- Running the app uses live mainnet TCP for BIP-157
