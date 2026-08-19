# KMP peers-discovery

Date: 2026-08-19  
Status: approved (conversation)

## Goal

Port helix3 peer discovery into Blueberry.kmp so it crawls Bitcoin mainnet, writes peers through `:storage`, and drives the main screen over `:bus`.

Discovery starts only after the onboarding gate is `Start`: already onboarded on cold start, or in the same process right after onboarding finishes. The main screen shows helix3 peer counts. Click me moves into Settings as **Self-diagnostics**.

This supersedes the onboarding spec’s “gate `Start` shows Click me” destination. Gate rules, persist, and onboarding screens are unchanged.

## Non-goals

- File logging (`initFileLog`, `--log`, log-line unit assertions)
- helix3 TUI chrome (Panel, magenta box, key hints)
- Headers, filters, blocks, broadcast, or sync-idle modules
- Process re-exec after onboarding
- Opening a helix3 `.sqlite` file
- Changing `:storage`, `:bus`, or `:wallet` public APIs
- Compose UI tests
- Live mainnet in default Gradle test tasks (the running app does use live mainnet)
- A separate `:net` Gradle module (net lives inside `:peers` for this port)

## Decisions

| Topic | Choice |
| --- | --- |
| Module split | New Gradle module `:peers` holds discovery + net. `:shared` holds store, Peers screen, Settings, App wiring. |
| Start destination | Peers tile, not Click me. |
| Click me | Settings section titled `Self-diagnostics`. Inner **Click me!** button and vendor lines stay (vendor-click spec). |
| Settings vs discovery | Opening Settings does not stop the module. |
| After onboarding | Re-run the gate in process. Start discovery. No re-exec. |
| Logging | `log` / `logError` call sites stay. Implementation is a no-op, same as `:wallet`. |
| Config | Only `peerProbeTimeoutMs` and `peerConcurrency` in `:peers`. |
| Tests | Injected DNS/probe. No DNS or `:8333` in CI. |

## Stack

- New Gradle module `:peers`
- Targets: Android, iOS (`iosArm64`, `iosSimulatorArm64`), desktop JVM (same as `:bus`)
- Package: `io.bluewallet.blueberry.peers` with subpackages `modules` and `net`
- `:peers` depends on `:storage`, `:bus`, `io.bluewallet:bip324`, `io.bluewallet:bip157`, `kotlinx-coroutines-core`
- `:shared` depends on `:peers`
- Do not change Kotlin `2.4.10`
- Add `kotlinx-coroutines-core` (and test) to the root version catalog (only the Swing artifact exists today)
- Android app: `android.permission.INTERNET` (not present today; required for live DNS/TCP)

## helix3 map

| helix3 | Kotlin |
| --- | --- |
| `src/modules/types.ts` | `modules/Types.kt` |
| `src/modules/detach-loop.ts` | `modules/DetachLoop.kt` |
| `src/modules/peers-discovery.ts` | `modules/PeersDiscovery.kt` |
| `src/net/types.ts` | `net/Types.kt` |
| `src/net/dns-seeds.ts` | `net/DnsSeeds.kt` |
| `src/net/peer-probe.ts` | `net/PeerProbe.kt` |
| `src/net/node-platform.ts` | `net/PlatformNet.kt` expect + android/jvm/ios actuals |
| `src/net/user-agent.ts` | `net/UserAgent.kt` |
| `config.peerProbeTimeoutMs` / `peerConcurrency` | `Config.kt` |
| `src/tui/peer-sockets-store.ts` | `:shared` `PeerSocketsStore.kt` |
| `hydratePeers` | `store.setKnown(db.peers.count())` |
| `src/tui/components/Peers.tsx` copy | `:shared` Peers screen |
| `src/main.tsx` `startApp` slice | `:shared` `App.kt` |

Do not port `src/net/format-error.ts` (only used by file logging).

## Units

| Unit | Job | Depends on |
| --- | --- | --- |
| `Module` / `ModuleContext` | `name`, `start`/`stop`; `bus` + `db` | `:bus`, `:storage` |
| `detachLoop` | Catch a fire-and-forget loop; emit `module:status` error | `ModuleContext` |
| `PlatformNet` | TCP `connect` + DNS `resolve4`/`resolve6` | bip324 `ByteDuplex` |
| `resolveSeedPeers` | Shuffle A then AAAA candidates, `services = 0` | `DnsResolver` |
| `probePeer` | Connect, BIP-324 + version/verack, do not wait for getaddr | `TcpConnect` |
| `createPeersDiscoveryModule` | Crawl loop, SQLite, bus events | all of the above |
| `PeerSocketsStore` | `known` + per-kind open counts | `:bus` kinds |
| Peers screen | Two helix3 lines + Settings button | store |
| Settings | Clear storage, Self-diagnostics, Back | existing Click me content |
| `App` | Start/stop discovery only while gate is `Start` | module, bus, store, net |

## Public API

Names match helix3 exports. `bigint` services are `ULong`. Promises are `suspend`. `AbortSignal` is coroutine cancellation.

### Module

```kotlin
data class ModuleContext(
    val bus: MessageBus,
    val db: Database,
)

interface Module {
    val name: String
    suspend fun start()
    fun stop()
}

fun detachLoop(ctx: ModuleContext, module: String, task: Job)
```

`start()` does not await DNS bootstrap or the crawl loop. Tests may still `runBlocking { mod.start() }`.

`detachLoop` attaches a completion handler: on failure emit `Event.ModuleStatus` with `ModuleStatus.ERROR` and `detail`, then `logError`. It does not rethrow.

### Net

```kotlin
data class PeerCandidate(val host: String, val port: Int, val services: ULong)

interface DnsResolver {
    suspend fun resolve4(host: String): List<String>
    suspend fun resolve6(host: String): List<String>
}

typealias TcpConnect = suspend (host: String, port: Int) -> ByteDuplex

data class PlatformNet(
    val connect: TcpConnect,
    val dns: DnsResolver,
)

expect fun createPlatformNet(): PlatformNet

val MAINNET_DNS_SEEDS: List<String> // same seven hostnames as helix3, frozen order

suspend fun resolveSeedPeers(
    seeds: List<String>,
    port: Int,
    resolver: DnsResolver,
    random: () -> Double = { kotlin.random.Random.nextDouble() },
    timeoutMs: Long = 3_000,
): List<PeerCandidate>
```

`resolveSeedPeers` matches helix3: resolve every seed’s A and AAAA concurrently; each family call has `timeoutMs` (default 3000); throw or hang returns `[]` for that family; IPv4 candidates (shuffled) then IPv6 (shuffled); each candidate `services = 0uL` and the given port.

### Probe

```kotlin
sealed class ProbeResult {
    data class Ok(val peers: List<PeerCandidate>, val services: ULong) : ProbeResult()
    data class Err(val error: String) : ProbeResult()
}

data class HandshakeResult(val peers: List<PeerCandidate>, val services: ULong)

class ProbeOptions(
    val timeoutMs: Long? = null,
    val connect: TcpConnect,
    val handshakeAndGetAddr: (suspend (ByteDuplex, Int) -> HandshakeResult)? = null,
)

suspend fun probePeer(host: String, port: Int, options: ProbeOptions): ProbeResult
```

Default handshake: `Protocol.connect(duplex, ProtocolOptions(role = Initiator, network = Networks.mainnet))` then `completeVersionHandshake` with `APP_NAME` / `APP_VERSION` and the probe port. Return `peers = emptyList()` and the peer’s `services`. Do not wait for addr/addrv2.

Timeout: `withTimeout(timeoutMs ?: Config.peerProbeTimeoutMs)`. On timeout or any throw: close the duplex if it exists, return `Err` with `message` or `toString()`. A timeout that fires during connect still closes a duplex that arrives afterwards (same as helix3 `connectOrAbort`).

### Discovery

```kotlin
class PeersDiscoveryOptions(
    val net: PlatformNet,
    val resolveSeeds: (suspend () -> List<PeerCandidate>)? = null,
    val probe: (suspend (host: String, port: Int) -> ProbeResult)? = null,
    val concurrency: Int? = null,
    val idleDelayMs: Long? = null,
    val probeTimeoutMs: Long? = null,
    val now: (() -> Long)? = null,
    val minAliveCompactFilters: Int? = null,
    val reseedIntervalMs: Long? = null,
)

fun createPeersDiscoveryModule(
    ctx: ModuleContext,
    options: PeersDiscoveryOptions,
): Module
```

Defaults: `concurrency` = `Config.peerConcurrency` (30), `idleDelayMs` = 500, `probeTimeoutMs` = `Config.peerProbeTimeoutMs` (3000), `now` = current epoch ms, `minAliveCompactFilters` = 16, `reseedIntervalMs` = 60_000, port = `Networks.mainnet.defaultPort` (8333). Default `resolveSeeds` / `probe` use `options.net`.

Loop behaviour is 1:1 with helix3 `peers-discovery.ts`:

- `start`: if already running, return. Emit `module:status` starting, `log` start, subscribe `sync:idle` / `sync:catchup` / `peers:updated`, fire bootstrap without awaiting, `detachLoop` the crawl, emit running.
- `stop`: if already stopped, return. Unsubscribe, clear pause/idle flags, wake waiters, clear inflight, emit `peers:sockets` probe open 0, emit stopped. In-flight probe completions after stop do not write SQLite.
- Bootstrap DNS only when `listAlive()` is empty.
- `maybeReseed` when alive compact-filter count `< minAliveCompactFilters` and reseed interval elapsed.
- Compact-filter bit is `NODE_COMPACT_FILTERS.toULong()` (64).
- Successful probe: `markProbed`, upsert source with `alive = true` and `result.services`, upsert neighbors as not alive, `markAlive(true)`, `peers:updated`.
- Failed probe: `markProbed`, `markAlive(false)`, `peers:updated`.
- Pause probes only while `syncIdle && listAlive().isNotEmpty()`. `sync:catchup` clears idle. `peers:updated` while idle re-evaluates pause.
- `peers:sockets` kind `PROBE`, `open = inflight.size`.
- Between iterations, yield (helix3 `setTimeout(0)`).

The module owns a `SupervisorJob` + `Dispatchers.Default` created in `start()`. `stop()` sets `stopped`, wakes waiters, then cancels that scope. Probe bodies check `stopped` before SQLite writes; `CancellationException` after stop is ignored (no persist). App does not pass a scope.

### Store (in `:shared`)

```kotlin
data class PeerSocketCounts(
    val known: Int = 0,
    val probe: Int = 0,
    val hdr: Int = 0,
    val filt: Int = 0,
    val blk: Int = 0,
)

interface PeerSocketsStore {
    fun get(): PeerSocketCounts
    fun setKnown(known: Int)
    fun applyEvent(kind: PeerSocketKind, open: Int)
    fun subscribe(listener: () -> Unit): () -> Unit
}

fun createPeerSocketsStore(): PeerSocketsStore
fun formatPeerSockets(counts: PeerSocketCounts): String
```

`formatPeerSockets` is exactly `probe ${counts.probe} · hdr ${counts.hdr} · filt ${counts.filt} · blk ${counts.blk}`.

`setKnown` / `applyEvent` clamp with `max(0, value)`. No-op if the field is unchanged (no notify).

## Type map

| helix3 | Kotlin |
| --- | --- |
| `bigint` services | `ULong` |
| `number` port / counts / concurrency | `Int` |
| `number` timestamps / delays / timeouts | `Long` |
| `AbortSignal` | coroutine cancellation |
| `() => number` random in `[0,1)` | `() -> Double` |
| `{ ok: true, peers, services }` | `ProbeResult.Ok` |
| `{ ok: false, error }` | `ProbeResult.Err` |
| `"probe" \| "hdr" \| "filt" \| "blk"` | existing `PeerSocketKind` |
| `status: "starting" \| …` | existing `ModuleStatus` |

## Constants

```kotlin
object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerConcurrency: Int = 30
}

const val APP_NAME = "blueberry"
const val APP_VERSION = "2026.08.17"
```

`MAINNET_DNS_SEEDS` (order fixed):

1. `seed.bitcoin.sipa.be`
2. `dnsseed.bluematt.me`
3. `seed.bitcoin.jonasschnelli.ch`
4. `seed.btc.petertodd.net`
5. `seed.bitcoin.sprovoost.nl`
6. `dnsseed.emzy.de`
7. `seed.bitcoin.wiz.biz`

User agent in version handshake is `/blueberry:2026.08.17/` (bip324 builds `/name:version/`).

## App lifecycle

`App` creates bus, net, store, and the discovery module only while `gate is OnboardingGate.Start`. That runtime is owned by `App`, not by the Peers composable.

| Gate | Discovery | UI |
| --- | --- | --- |
| `Onboard` | not started | onboarding |
| `ExitInvalid` | not started | invalid-secret screen |
| `Start` | started | Peers screen, or Settings on top without stopping discovery |

**Cold start onboarded:** open DB → `Start` → `createMessageBus()`, `createPlatformNet()`, `createPeerSocketsStore()`, `createPeersDiscoveryModule(ctx, PeersDiscoveryOptions(net))` → subscribe:

- `Event.PeersUpdated` → `store.setKnown(db.peers.count())`
- `Event.PeersSockets` → `store.applyEvent(kind, open)`

then `setKnown(db.peers.count())`, then `module.start()`.

**After onboarding:** `onFinished` → `refreshGate()` → `Start` → same path in this process.

**Dispose:** `stop()` when leaving `Start` (session bump, clear storage, `App` leaves composition). SQLite writes from probes that finish after `stop()` are ignored.

**Clear storage:** existing session++ path. Discovery stops with the old DB; new gate is onboarding.

`start()` throw: emit `module:status` error for `peers-discovery` and keep the Peers screen (last hydrated counts).

## Screens

Material3. Existing `MaterialTheme`. No TUI Panel chrome.

**Start (Peers):**

1. Title `Peers`
2. `formatPeerSockets(counts)` (`probe 0 · hdr 0 · filt 0 · blk 0` until events)
3. `{known} known`
4. Button `Settings`

`hdr` / `filt` / `blk` stay `0` until those modules exist. `probe` and `known` move as discovery runs.

**Settings:**

1. Title `Settings`
2. Button `Clear storage` (unchanged)
3. Heading `Self-diagnostics`, then current Click me UI (button label **Click me!**, vendor lines, Compose logo)
4. TextButton `Back`

## Platform net

`createPlatformNet()` expect/actual.

**DNS**

- `resolve4` / `resolve6` return address strings for that family only.
- Android/desktop: `InetAddress.getAllByName` filtered by `Inet4Address` / `Inet6Address`. Duplicate lookups from the two methods are acceptable.
- iOS: `getaddrinfo` with `AF_INET` / `AF_INET6`.
- Throws become empty lists at `resolveSeedPeers` (the resolver may throw; `resolveSeedPeers` catches per family).

**TCP**

- `connect(host, port)` on `Dispatchers.IO`, return a bip324 `ByteDuplex`.
- Android/desktop: `java.net.Socket`.
- iOS: POSIX `socket` / `connect` / `read` / `write` / `close`.
- IPv4 and IPv6 hosts from DNS must connect. `read` may return 1..n bytes; empty only at EOF. `close` is idempotent.
- Cancellation of the caller (probe timeout) closes the socket.

## Errors

| Case | Behavior |
| --- | --- |
| Connect/handshake throw | `ProbeResult.Err` with the exception message |
| Probe timeout | `Err` containing timed out/aborted; duplex closed |
| DNS throw/hang in `pullSeeds` | `logError`; no crash; `lastReseedAt` still updates |
| Crawl loop throw | `detachLoop` → `module:status` error |
| `module.start()` throw in `App` | `module:status` error; Peers screen stays up |
| Probe finishes after `stop()` | No SQLite writes |

## Testing

TDD. Port helix3 unit tests. Default Gradle tests never call live DNS or mainnet TCP.

`:peers` `commonTest`:

- `dns-seeds.test.ts` → `DnsSeedsTest` (v4 before v6, skip throwing seed, concurrent seeds, hanging seed, hanging AAAA keeps A)
- `peer-probe.test.ts` → `PeerProbeTest` (connect fail → Err, timeout closes duplex, default handshake succeeds without getaddr using `pairedByteDuplexes`)
- `peer-probe-services.test.ts` → `PeerProbeServicesTest` (`NODE_COMPACT_FILTERS` through injected handshake)
- `peers-discovery.test.ts` → `PeersDiscoveryTest` (all behaviour tests). Drop assertions that only check file-log text (`[peers-discovery] start|dns|stop|probe fail|pause|resume`). Keep pause/resume probe-count behaviour.

`:shared` `commonTest`:

- Store merge/clamp/no-op (tui-peer-sockets first test)
- Seed `known` from `db.peers.count()`, apply `peers:sockets`, increment `known` on `peers:updated` after another upsert (tui-module slice; no full TUI)

Use `waitFor` polling like helix3 (real `Dispatchers.Default` + delays). Tests use `createSqliteDatabase(":memory:")` and stub `PlatformNet` / injected `resolveSeeds` / `probe`.

Pass:

- `./gradlew :peers:jvmTest :peers:testAndroidHostTest`
- `./gradlew :shared:jvmTest :shared:testAndroidHostTest`
- `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :shared:compileKotlinJvm`

iOS simulator tests stay optional on Linux.

Manual check after implement: desktop or Android, onboarded wallet. Main screen shows `0 known` then rising `known` and non-zero `probe` while crawling. Settings still opens; discovery keeps running. Self-diagnostics still toggles vendor lines. Clear storage returns to onboarding.

## Success

- Gate `Start` starts discovery; onboarding does not
- Peers persist in SQLite with the same upsert / alive / services rules as helix3
- Main screen shows `probe N · hdr N · filt N · blk N` and `N known` from the bus + DB
- Click me lives under Settings as Self-diagnostics
- Helix3 behaviour tests (minus file-log assertions) pass on JVM and Android host
- Running the app uses live mainnet DNS and TCP
