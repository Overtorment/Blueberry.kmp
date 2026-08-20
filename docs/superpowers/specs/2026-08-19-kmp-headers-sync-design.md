# KMP headers sync

Date: 2026-08-19  
Status: approved (conversation; remaining section approvals skipped at user request)

## Goal

Port helix3 chain-header sync into Blueberry.kmp so it downloads mainnet headers over BIP-324, validates with `bitcoin-headers`, persists through `:storage`, and drives a Chain tip tile plus `hdr` socket counts on the Start screen.

Sync starts only after the onboarding gate is `Start`, next to peers-discovery. Consensus comes from the stored `sync_from_year` via helix3 `CHECKPOINTS`.

## Non-goals

- File logging (`initFileLog`, `--log`, log-line unit assertions)
- helix3 TUI chrome (Panel, magenta box, key hints)
- Filters, blocks, broadcast, or sync-idle modules (idle/catchup *listeners* stay; nothing emits them yet)
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
| Module split | `header-sync` + header config live in `:peers/net`. New `:headers` holds checkpoint, trusted-chain, `chain-headers`. `:shared` holds progress store, hydrate, Chain tip UI, runtime wiring. |
| UI | Chain tip tile + `hdr` sockets on the existing Start screen (helix3). |
| Checkpoint | Port helix3 `CHECKPOINTS` 2009–2026. App passes `consensusForYear(loadSyncFromYear(db))`. Module does not read KV. |
| Behaviour source | Current helix3 `chain-headers.ts` / `header-sync.ts` (race pool, mark-not-alive, birthday freeze, quiet idle), not the 2026-08-01 sequential spec. |
| Settings | Opening Settings does not stop headers or discovery. |
| Logging | `log` / `logError` call sites stay. Implementation is a no-op, same as `:peers`. |
| Tests | Injected `fetchBatch` / `openSession`. No live `:8333` in CI. Drop file-log text assertions. |

## Stack

- New Gradle module `:headers`
- Targets: Android, iOS (`iosArm64`, `iosSimulatorArm64`), desktop JVM (same as `:peers`)
- Package: `io.bluewallet.blueberry.headers` with subpackage `modules`
- `:headers` depends on `:peers` (api: `Module`, `PlatformNet`, `HeaderSessionPool`), `:storage`, `:bus`, `:wallet` (birthday), `io.bluewallet:bitcoin-headers`, `io.bluewallet:bip324`, `kotlinx-coroutines-core`
- `:shared` depends on `:headers`
- Header-sync types live in `io.bluewallet.blueberry.peers.net`
- Do not change Kotlin `2.4.10`

## helix3 map

| helix3 | Kotlin |
| --- | --- |
| `config.headerSyncTimeoutMs` / `headerRacePeers` | `:peers` `Config` |
| `src/net/header-sync.ts` | `:peers` `net/HeaderSync.kt` |
| `src/checkpoint.ts` | `:headers` `Checkpoint.kt` |
| `src/headers/trusted-chain.ts` + `hash.ts` | `:headers` `TrustedChain.kt` |
| `src/modules/chain-headers.ts` | `:headers` `modules/ChainHeaders.kt` |
| `src/tui/headers-progress-store.ts` + `progress-eta.ts` | `:shared` `HeadersProgressStore.kt` |
| `formatEta` / `progressBar` (width 10) | `:shared` `ProgressFormat.kt` |
| `hydrateHeaders` | `:shared` next to `hydratePeers` |
| `ChainTipSync.tsx` copy | `:shared` Chain tip block on Start screen |
| `startApp` headers slice | expand `PeersRuntime` |

Reuse `:peers` `Module` / `ModuleContext` / `detachLoop` / `PlatformNet`. Do not re-port those types.

## Units

| Unit | Job | Depends on |
| --- | --- | --- |
| `Config` header fields | `headerSyncTimeoutMs = 30_000`, `headerRacePeers = 10` | none |
| `HeaderSessionPool` / `fetchHeadersBatch` | BIP-324 getheaders session reuse | `PlatformNet`, bip324 |
| `Checkpoint` | Year table → consensus + DB seed | bitcoin-headers, `:storage` HeaderRecord |
| `trustedChainFromStored` | Rebuild validated chain without re-checking consensus | bitcoin-headers, stored rows |
| `createChainHeadersModule` | Race, validate, persist, emit | pool or injected fetch, checkpoint, wallet birthday |
| `HeadersProgressStore` | percent, ETA, height | none |
| `hydrateHeaders` | DB span + durable total | store, `:storage` |
| Start screen Chain tip | bar, counts, height, ETA | store |
| `PeersRuntime` | Start/stop both modules after gate `Start` | `:peers`, `:headers` |

## Public API

Names match helix3 exports. Promises are `suspend`. `AbortSignal` is coroutine cancellation. Storage heights stay `Int`; bitcoin-headers heights stay `Long` — convert at the boundary.

`HeaderBatchResult.headers` uses `io.bluewallet.headers.BlockHeader` (bitcoin-headers). Production getheaders converts bip324 wire headers. Helix3 shares one JS type; KMP does not.

### `:peers` Config

```kotlin
object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerConcurrency: Int = 30
    const val headerSyncTimeoutMs: Long = 30_000
    const val headerRacePeers: Int = 10
}
```

### Header sync (`:peers` `net/HeaderSync.kt`)

```kotlin
const val SESSION_BUSY_ERROR = "session busy"

sealed class HeaderBatchResult {
    data class Ok(val startHeight: Int, val headers: List<io.bluewallet.headers.BlockHeader>) : HeaderBatchResult()
    data class Err(val error: String) : HeaderBatchResult()
}

class HeaderFetchOptions(
    val locatorHashes: List<ByteArray>,
    val stopHash: ByteArray? = null,
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
)

class HeaderSyncOptions(
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val locatorHashes: List<ByteArray>,
    val stopHash: ByteArray? = null,
    val connect: TcpConnect,
    val requestHeaders: (suspend (ByteDuplex, Int, List<ByteArray>, ByteArray) -> HeaderRequestResult)? = null,
)

class HeaderRequestResult(
    val startHeight: Int,
    val headers: List<io.bluewallet.headers.BlockHeader>,
)

class OpenedHeaderSession(
    val startHeight: Int,
    val requestHeaders: suspend (List<ByteArray>, ByteArray) -> HeaderRequestResult,
    val close: suspend () -> Unit,
)

class HeaderSessionPoolOptions(
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val connect: TcpConnect? = null,
    val openSession: (suspend (String, Int) -> OpenedHeaderSession)? = null,
    val onOpenCount: ((Int) -> Unit)? = null,
    val max: Int? = null,
)

interface HeaderSessionPool {
    fun has(host: String, port: Int): Boolean
    fun isBusy(host: String, port: Int): Boolean
    fun isFull(): Boolean
    suspend fun fetchBatch(host: String, port: Int, options: HeaderFetchOptions): HeaderBatchResult
    suspend fun drop(host: String, port: Int)
    suspend fun closeAll()
}

fun createHeaderSessionPool(options: HeaderSessionPoolOptions = HeaderSessionPoolOptions()): HeaderSessionPool
suspend fun fetchHeadersBatch(host: String, port: Int, options: HeaderSyncOptions): HeaderBatchResult
```

Pool behaviour is 1:1 with helix3 `header-sync.ts`:

- Handshake once (`Protocol.connect` initiator/mainnet + `completeVersionHandshake` with `APP_NAME` / `APP_VERSION`), reuse for many `getheaders`.
- Default `getheaders` version `70016`, `stopHash` zeros. Read until `headers`; `answerPing` otherwise.
- Cap `max ?: headerRacePeers * 2`. Live + opening count. `onOpenCount` only when the count changes.
- Second fetch on a connecting or busy session, or when full: `Err(SESSION_BUSY_ERROR)` without dropping.
- Failed getheaders / handshake: drop session, `Err` with message.
- `closeAll` increments epoch so an open that finishes after close is dropped (`Err("session closed")`).
- Connect/handshake timeout vs download timeout via `withTimeout`. Cancelled connect still closes the duplex (same as `probePeer` `connectOrAbort`).

### Checkpoint

```kotlin
data class YearCheckpoint(
    val name: String,
    val height: Int,
    val headerHex: String,
    val previousTimestamps: List<Long>,
)

const val DEFAULT_CHECKPOINT_YEAR = 2019
val CHECKPOINTS: Map<Int, YearCheckpoint> // 2009..2026, exact helix3 hex

fun checkpointForYear(year: Int): YearCheckpoint
fun consensusForYear(year: Int): HeaderConsensusParams
fun checkpointDbRecord(year: Int = DEFAULT_CHECKPOINT_YEAR): io.bluewallet.blueberry.storage.HeaderRecord
fun checkpointSeedRecord(year: Int = DEFAULT_CHECKPOINT_YEAR): CheckpointSeed

val CHECKPOINT_HEIGHT: Int // 556416
val BLUEBERRY_HEADER_CONSENSUS: HeaderConsensusParams
```

`consensusFrom` matches helix3: `MAINNET_POW_LIMIT`, 10-minute spacing, 2-week timespan, retarget 2016, MTP 11, max future 2 hours. Unknown year throws `unknown checkpoint year: $year`. `checkpointSeedRecord` fails if the header fails PoW.

### Trusted chain

```kotlin
const val TRUSTED_CHAIN_WINDOW = 4096

fun internalHexToDisplayHex(internalHex: String): String
fun trustedChainFromStored(
    records: List<StoredHeader>,
    params: HeaderConsensusParams,
): ValidatedHeaderChain
```

Rebuild from DB rows that were validated at write time. Do **not** re-check PoW, links, nBits, or MTP. Empty list or a height gap throws. `cumulativeWork` comes from the stored row.

### Chain headers

```kotlin
class ChainHeadersOptions(
    val net: PlatformNet,
    val fetchBatch: (suspend (String, Int, HeaderFetchOptions) -> HeaderBatchResult)? = null,
    val connectTimeoutMs: Long? = null,
    val headersTimeoutMs: Long? = null,
    val racePeers: Int? = null,
    val pollIntervalMs: Long? = null,
    val consensus: HeaderConsensusParams? = null,
    val now: (() -> Long)? = null,
    val nowSeconds: (() -> Long)? = null,
)

fun createChainHeadersModule(ctx: ModuleContext, options: ChainHeadersOptions): Module
```

Defaults: `connectTimeoutMs` = `Config.peerProbeTimeoutMs`, `headersTimeoutMs` = `Config.headerSyncTimeoutMs`, `racePeers` = `Config.headerRacePeers`, `pollIntervalMs` = 30_000, `consensus` = `BLUEBERRY_HEADER_CONSENSUS`, `now` = epoch ms, `nowSeconds` = floor epoch seconds.

`name` is `chain-headers`.

Loop is 1:1 with helix3 `chain-headers.ts`:

- `start`: if running, return. Emit starting, log start, `stopped = false`, `ensureCheckpoint` from consensus, subscribe `sync:idle` / `sync:catchup` / `peers:updated`, `detachLoop(runLoop)`, emit running. No startup `headers:progress` (unknown total would clobber the TUI seed).
- `stop`: if stopped, return. Set `stopped`, unsubscribe, clear quiet/waiting, kick waiters, `closeAll` pool (if any), join the loop (do not cancel the in-flight `fetchBatch`; match helix3), `closeAll` again, emit stopped. Injected `fetchBatch` means no pool.
- Wait for `listAlive()` minus session-dead. Empty alive + empty allAlive: log waiting once, `waitForKick(pollIntervalMs)`. Empty alive but some session-dead: clear dead/skipped/sticky, `waitForKick(250)`.
- Race up to `racePeers`: prefer sticky, then pooled idle sessions, then walk `peerIndex`. Skip busy/full new opens. Empty pick: if all skipped, clear skipped; `waitForKick(100)`.
- `raceHeaderFetch`: first non-empty ok wins. Empty ok waits for in-flight peers. `SESSION_BUSY_ERROR` is soft. Hard fail / thrown fetch: mark dead. Late responses after a winner are ignored.
- Hard fail: add to dead, `pool.drop`, `peers.markAlive(false)`, emit `peers:updated`, clear sticky if that peer. Consensus `HeaderConsensusError` is a hard fail.
- Applied append/replace: persist, emit progress, try freeze birthday, keep winner sticky, continue (no poll delay).
- Weaker fork: skip that peer, clear sticky.
- Empty winning batch: clamp `maxPeerStartHeight` to local tip, emit progress, freeze birthday if caught up, log `at tip` once per height, `waitForKick(pollIntervalMs)`.
- Instant fail / busy-only: `waitForKick(busyOnly ? 100 : 500)`.
- `headers:progress`: skip if `maxPeerStartHeight <= checkpointHeight`. `downloaded = tip - checkpoint`. `total = max(maxPeerStartHeight, tip) - checkpoint`. Include `height`.
- `peers:sockets` kind `HDR` from pool `onOpenCount`. Injected fetch does not emit hdr counts.
- Reorg replace: `db.transaction { rewindAfter(ancestor); headers.replaceAfter(ancestor, writes) }`, then emit `wallet:txs` and `blocks:progress` (downloaded = `blocks.count()`, matched = `matchedBlocks.count()`).
- `maybeFreezeWalletBirthday(db, tipHeight)` only when `tip >= maxPeerStartHeight` and max is above checkpoint.
- `sync:idle` sets quiet. `sync:catchup` clears quiet and kicks. `peers:updated` while quiet kicks only if `waitingForPeers`.
- Locator: tip hash, then step 1 until 10 hashes, then double step, max 32, always include checkpoint if tip is above it.
- Trusted window: load `max(checkpoint, through - 4096) .. through`. Trim in-memory chain by reloading when `headers.size > 8192`.
- `log` / `logError` call sites stay (no-op). Do not assert log text in tests.

`stop()` is synchronous (`Module.stop`). Join the loop with `runBlocking` (helix3 awaits `loopPromise`). Do not cancel the fetch job first — the “stop waits for in-flight” test requires that.

### Progress store (`:shared`)

```kotlin
data class HeadersProgress(
    val downloaded: Int = 0,
    val total: Int = 0,
    val height: Int = 0,
    val at: Long? = null,
    val etaMs: Long? = null,
    val percent: Int = 0,
)

interface HeadersProgressStore {
    fun get(): HeadersProgress
    fun applyEvent(at: Long, downloaded: Int, total: Int, height: Int)
    fun subscribe(listener: () -> Unit): () -> Unit
}

fun createHeadersProgressStore(): HeadersProgressStore
fun hydrateHeaders(
    db: Database,
    store: HeadersProgressStore,
    peerTotal: Int? = null,
    at: Long = currentTimeMillis(),
)
fun formatEta(etaMs: Long?): String
fun progressBar(percent: Int, width: Int = 10): String
```

Store / ETA match helix3 `headers-progress-store.ts` + `progress-eta.ts` (max 8 advancing samples; reset on regress or leave-complete; `formatEta(null)` is `—`; `<= 0` is `done`).

`hydrateHeaders`: if no tip or minHeight, return. `downloaded = max(0, tip.height - minHeight)`. Total = incoming if `> 0`, else previous store total if `> 0`, else downloaded. Height from DB tip. On `headers:progress`, call `hydrateHeaders(db, store, p.total, p.at)` — incoming `total == 0` must not clobber a DB seed.

### App lifecycle

`PeersRuntime` (same owner as today) also creates `HeadersProgressStore`, binds `headers:progress` → hydrate, hydrates headers from DB **before** starting modules, then starts `chain-headers` after peers-discovery `start()` (helix3 starts TUI first, then domain modules; we have no TUI module, so hydrate-then-start is the equivalent).

```kotlin
createChainHeadersModule(
    ModuleContext(bus, db),
    ChainHeadersOptions(
        net = net,
        consensus = consensusForYear(loadSyncFromYear(db)),
    ),
)
```

`start()` throw for either module: emit `module:status` error for that module name; keep the Start screen.

`stop()`: stop headers, then discovery, then unbind. Opening Settings does not stop.

### Screens

Material3. Existing `MaterialTheme`. No TUI Panel chrome.

**Start** (existing Peers screen, plus Chain tip):

1. Title `Peers`
2. `formatPeerSockets(counts)`
3. `{known} known`
4. Title `Chain tip`
5. `progressBar(percent, 10)`
6. `{downloaded}/{total}`
7. `{height} tip`
8. `ETA {formatEta(etaMs)}` only while `percent < 100`
9. Button `Settings`

## Errors

| Case | Behavior |
| --- | --- |
| Connect/handshake/download throw | `HeaderBatchResult.Err`; session dropped |
| Timeout | `Err` containing timed out/aborted; duplex closed |
| `SESSION_BUSY_ERROR` | Soft; not peer death |
| Batch does not link / consensus fail | Do not persist; hard-fail that peer |
| Weaker fork | Skip peer; keep canonical |
| Loop throw | `detachLoop` → `module:status` error |
| `module.start()` throw in App | `module:status` error; Start screen stays |
| Checkpoint year unknown | `checkpointForYear` throws (gate `Start` already validated the year) |
| Checkpoint mismatch in DB | `ensureCheckpoint` throws (existing storage message) |

## Testing

TDD. Port helix3 unit tests. Default Gradle tests never call live mainnet TCP.

`:peers` `commonTest` (add):

- `header-sync.test.ts` → `HeaderSyncTest` (connect fail, connect timeout closes duplex, headers timeout on injected request, locator/stopHash forwarded)
- `header-session-pool.test.ts` → `HeaderSessionPoolTest` (reuse, busy, drop on fail, IPv6 closeAll, closeAll during open, connecting busy, handshake timeout closes duplex, max cap)

`:headers` `commonTest`:

- `trusted-chain.test.ts` → `TrustedChainTest` (checkpoint load, window constant > 2016)
- `checkpoint` year/height/2019 default/unknown year
- `chain-headers.test.ts` → `ChainHeadersTest` (all behaviour tests). Drop assertions that only check file-log text. Keep waiting/append/progress/reorg/race/backoff/idle/birthday/locator/stop-join.

`:shared` `commonTest`:

- `headers-progress-store.test.ts` → percent/ETA/non-advancing
- `hydrateHeaders` + bus: DB seed, `total: 0` does not clobber, `total > 0` updates total
- Runtime bind: hydrate then progress event

Use `waitFor` polling. Tests use `:memory:` SQLite and injected `fetchBatch` / `openSession`. Easy-difficulty mined chains for reorg/locator (port helix3 `mineHeader` helpers).

Pass:

- `./gradlew :peers:jvmTest :peers:testAndroidHostTest`
- `./gradlew :headers:jvmTest :headers:testAndroidHostTest`
- `./gradlew :shared:jvmTest :shared:testAndroidHostTest`
- `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :headers:compileKotlinJvm :shared:compileKotlinJvm`

iOS simulator tests stay optional on Linux.

Manual check after implement: desktop or Android, onboarded wallet. Start screen shows Chain tip hydrating from checkpoint (`0/0` or `N/N` after restart), then rising `downloaded/total` and `hdr` while peers exist. Settings still opens; sync keeps running.

## Success

- Gate `Start` starts chain-headers with `consensusForYear(sync_from_year)`
- Fresh DB seeds that year’s checkpoint; sync only builds on top
- Alive peers accumulate headers; each applied persist emits `headers:progress`
- Reorg uses `rewindAfter` + `replaceAfter` and wakes wallet/blocks events
- Start screen shows Chain tip bar, counts, height, ETA; Peers line shows `hdr`
- Helix3 behaviour tests (minus file-log assertions) pass on JVM and Android host
- Running the app uses live mainnet TCP for getheaders
