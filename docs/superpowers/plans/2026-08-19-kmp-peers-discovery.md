# KMP peers-discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port helix3 peer discovery so it crawls, writes `:storage` peers, and drives the main screen over `:bus` after onboarding.

**Architecture:** New Gradle module `:peers` holds `Module`/`ModuleContext`, DNS seeds, BIP-324 `probePeer`, `PlatformNet` expect/actual, and `createPeersDiscoveryModule`. `:shared` owns `PeerSocketsStore`, the Peers screen, Settings Self-diagnostics, and App start/stop while the gate is `Start`.

**Tech Stack:** Kotlin 2.4.10, kotlinx-coroutines 1.11.0, `:storage`, `:bus`, `io.bluewallet:bip324`, `io.bluewallet:bip157`, java.net.Socket (Android/desktop), POSIX sockets (iOS).

**Spec:** `docs/superpowers/specs/2026-08-19-kmp-peers-discovery-design.md`

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-19-kmp-peers-discovery-design.md`.
- Behaviour source: `/home/ghost/Documents/Blueberry/src/modules/peers-discovery.ts`, `/home/ghost/Documents/Blueberry/src/net/dns-seeds.ts`, `/home/ghost/Documents/Blueberry/src/net/peer-probe.ts`, `/home/ghost/Documents/Blueberry/src/net/types.ts`, `/home/ghost/Documents/Blueberry/src/tui/peer-sockets-store.ts`, `/home/ghost/Documents/Blueberry/src/tui/tui-module.ts` peer slice, `/home/ghost/Documents/Blueberry/src/main.tsx` `startApp`.
- Package: `io.bluewallet.blueberry.peers` with subpackages `modules` and `net`. Store/UI stay `io.bluewallet.blueberry`.
- Public names match helix3 exports. `bigint` services → `ULong`. Promises → `suspend`. `AbortSignal` → coroutine cancellation.
- `Config.peerProbeTimeoutMs = 3_000`, `Config.peerConcurrency = 30`. `APP_NAME = "blueberry"`, `APP_VERSION = "2026.08.17"`.
- `log` / `logError` are no-ops. Do not port file logging. Do not assert `[peers-discovery]` log text.
- Default Gradle tests must not call live DNS seeds or mainnet `:8333`. Localhost TCP in `jvmTest` is allowed.
- Do not port headers/filters/blocks/broadcast/sync-idle modules. `hdr`/`filt`/`blk` stay `0`.
- Do not change `:storage`, `:bus`, or `:wallet` public APIs. Do not change Kotlin `2.4.10`.
- Do not add Compose UI tests. Do not re-exec after onboarding.
- Opening Settings must not stop discovery. Gate `Onboard` / `ExitInvalid` must not start it.
- Android `android.permission.INTERNET` is required.
- Pass `./gradlew :peers:jvmTest :peers:testAndroidHostTest`, `./gradlew :shared:jvmTest :shared:testAndroidHostTest`, `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :shared:compileKotlinJvm`.
- iOS simulator tests stay optional on Linux.
- Do not commit unless the user asks.

## File structure

```
settings.gradle.kts
gradle/libs.versions.toml
peers/build.gradle.kts
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Log.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Time.kt
peers/src/androidMain/kotlin/io/bluewallet/blueberry/peers/Time.android.kt
peers/src/jvmMain/kotlin/io/bluewallet/blueberry/peers/Time.jvm.kt
peers/src/iosMain/kotlin/io/bluewallet/blueberry/peers/Time.ios.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/Types.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/DetachLoop.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscovery.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/Types.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/UserAgent.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/DnsSeeds.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/PeerProbe.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.kt
peers/src/androidMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.android.kt
peers/src/jvmMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.jvm.kt
peers/src/iosMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.ios.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/WaitFor.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/StubPlatformNet.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/DnsSeedsTest.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/PeerProbeTest.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/PeerProbeServicesTest.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/modules/DetachLoopTest.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscoveryTest.kt
peers/src/jvmTest/kotlin/io/bluewallet/blueberry/peers/net/PlatformNetJvmTest.kt
shared/build.gradle.kts
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeerSocketsStore.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/SettingsScreen.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeerSocketsStoreTest.kt
androidApp/src/main/AndroidManifest.xml
```

---

### Task 1: `:peers` Gradle module, Config, Log, Time

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `peers/build.gradle.kts`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Log.kt`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Time.kt`
- Create: `peers/src/androidMain/kotlin/io/bluewallet/blueberry/peers/Time.android.kt`
- Create: `peers/src/jvmMain/kotlin/io/bluewallet/blueberry/peers/Time.jvm.kt`
- Create: `peers/src/iosMain/kotlin/io/bluewallet/blueberry/peers/Time.ios.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt`

**Interfaces:**
- Consumes: none
- Produces: Gradle project `:peers`; `object Config { const val peerProbeTimeoutMs: Long = 3_000; const val peerConcurrency: Int = 30 }`; `fun log(scope: String, message: String)`; `fun logError(scope: String, message: String, err: Throwable? = null)`; `internal expect fun currentTimeMillis(): Long`

- [ ] **Step 1: Write the failing test**

Create `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt`:

```kotlin
package io.bluewallet.blueberry.peers

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun helix3_probe_defaults() {
        assertEquals(3_000L, Config.peerProbeTimeoutMs)
        assertEquals(30, Config.peerConcurrency)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest`

Expected: FAIL — project `:peers` does not exist.

- [ ] **Step 3: Wire the module**

In `settings.gradle.kts` add `include(":peers")` next to `include(":bus")`.

In `gradle/libs.versions.toml` `[libraries]` add:

```toml
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
```

Create `peers/build.gradle.kts` (copy `:bus` structure; add deps):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64())
    jvm()
    android {
        namespace = "io.bluewallet.blueberry.peers"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest { }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":storage"))
            api(project(":bus"))
            api(libs.bip324)
            implementation(libs.bip157)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}
```

Create Config, Log, Time:

```kotlin
package io.bluewallet.blueberry.peers

object Config {
    const val peerProbeTimeoutMs: Long = 3_000
    const val peerConcurrency: Int = 30
}

fun log(scope: String, message: String) {}

fun logError(scope: String, message: String, err: Throwable? = null) {}

internal expect fun currentTimeMillis(): Long
```

`Time.android.kt` and `Time.jvm.kt`: `internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()`

`Time.ios.kt`:

```kotlin
package io.bluewallet.blueberry.peers

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest :peers:testAndroidHostTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml peers
git commit -m "$(cat <<'EOF'
Add a :peers Gradle module with helix3 probe config defaults.

EOF
)"
```

Skip the commit unless the user asked.

---

### Task 2: DNS seeds

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/Types.kt`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/DnsSeeds.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/DnsSeedsTest.kt`

**Interfaces:**
- Consumes: none from Task 1 except the module
- Produces: `data class PeerCandidate(val host: String, val port: Int, val services: ULong)`; `interface DnsResolver { suspend fun resolve4(host: String): List<String>; suspend fun resolve6(host: String): List<String> }`; `val MAINNET_DNS_SEEDS: List<String>`; `suspend fun resolveSeedPeers(seeds: List<String>, port: Int, resolver: DnsResolver, random: () -> Double = { kotlin.random.Random.nextDouble() }, timeoutMs: Long = 3_000): List<PeerCandidate>`

- [ ] **Step 1: Write the failing tests**

Create `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/DnsSeedsTest.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DnsSeedsTest {
    @Test
    fun resolveSeedPeers_returns_ipv4_before_ipv6_with_given_port() = runBlocking {
        val peers = resolveSeedPeers(
            listOf("seed.example"),
            port = 8333,
            resolver = object : DnsResolver {
                override suspend fun resolve4(host: String) = listOf("10.0.0.1")
                override suspend fun resolve6(host: String) = listOf("2001:db8::1")
            },
            random = { 0.0 },
        )
        assertEquals(listOf("10.0.0.1", "2001:db8::1"), peers.map { it.host })
        assertTrue(peers.all { it.port == 8333 && it.services == 0uL })
    }

    @Test
    fun skips_seeds_whose_resolver_throws() = runBlocking {
        val peers = resolveSeedPeers(
            listOf("bad", "good"),
            port = 8333,
            resolver = object : DnsResolver {
                override suspend fun resolve4(host: String): List<String> {
                    if (host == "bad") error("fail")
                    return listOf("9.9.9.9")
                }
                override suspend fun resolve6(host: String) = emptyList<String>()
            },
        )
        assertEquals(listOf("9.9.9.9"), peers.map { it.host })
    }

    @Test
    fun resolves_all_seeds_concurrently() = runBlocking {
        val started = mutableListOf<String>()
        val release = mutableMapOf<String, CompletableDeferred<List<String>>>()
        val pending = kotlinx.coroutines.async {
            resolveSeedPeers(
                listOf("a", "b"),
                port = 8333,
                resolver = object : DnsResolver {
                    override suspend fun resolve4(host: String): List<String> {
                        started.add(host)
                        val gate = CompletableDeferred<List<String>>()
                        release[host] = gate
                        return gate.await()
                    }
                    override suspend fun resolve6(host: String) = emptyList<String>()
                },
            )
        }
        while (started.size < 2) kotlinx.coroutines.delay(5)
        release["a"]!!.complete(listOf("10.0.0.1"))
        release["b"]!!.complete(listOf("10.0.0.2"))
        val peers = pending.await()
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), peers.map { it.host }.sorted())
    }

    @Test
    fun hanging_seed_does_not_block_other_seeds() = runBlocking {
        val peers = resolveSeedPeers(
            listOf("hang", "ok"),
            port = 8333,
            resolver = object : DnsResolver {
                override suspend fun resolve4(host: String): List<String> {
                    if (host == "hang") {
                        kotlinx.coroutines.CompletableDeferred<List<String>>().await()
                    }
                    return listOf("10.0.0.2")
                }
                override suspend fun resolve6(host: String) = emptyList<String>()
            },
            timeoutMs = 40,
        )
        assertEquals(listOf("10.0.0.2"), peers.map { it.host })
    }

    @Test
    fun keeps_ipv4_when_ipv6_hangs() = runBlocking {
        val peers = resolveSeedPeers(
            listOf("mixed"),
            port = 8333,
            resolver = object : DnsResolver {
                override suspend fun resolve4(host: String) = listOf("10.0.0.1")
                override suspend fun resolve6(host: String): List<String> {
                    kotlinx.coroutines.CompletableDeferred<List<String>>().await()
                    return emptyList()
                }
            },
            timeoutMs = 40,
        )
        assertEquals(listOf("10.0.0.1"), peers.map { it.host })
    }

    @Test
    fun mainnet_dns_seeds_match_helix3_order() {
        assertEquals(
            listOf(
                "seed.bitcoin.sipa.be",
                "dnsseed.bluematt.me",
                "seed.bitcoin.jonasschnelli.ch",
                "seed.btc.petertodd.net",
                "seed.bitcoin.sprovoost.nl",
                "dnsseed.emzy.de",
                "seed.bitcoin.wiz.biz",
            ),
            MAINNET_DNS_SEEDS,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.DnsSeedsTest`

Expected: FAIL — unresolved `resolveSeedPeers` / `DnsResolver` / `MAINNET_DNS_SEEDS`.

- [ ] **Step 3: Implement DNS seeds**

Create `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/Types.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex

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
```

Create `DnsSeeds.kt` porting helix3 `dns-seeds.ts`:

- `MAINNET_DNS_SEEDS` is the seven hostnames in the test order (immutable list).
- `shuffleInPlace`: Fisher-Yates using `j = kotlin.math.floor(random() * (i + 1)).toInt()`.
- `resolveFamily`: if `timeoutMs <= 0` await the task; else `withTimeout(timeoutMs)` and on throw or `TimeoutCancellationException` return `emptyList()`.
- `resolveSeedPeers`: `coroutineScope` + `async` per seed; each seed `awaitAll` of resolve4 and resolve6; collect v4 then v6; shuffle each family; `services = 0uL`.

```kotlin
package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.math.floor

val MAINNET_DNS_SEEDS: List<String> = listOf(
    "seed.bitcoin.sipa.be",
    "dnsseed.bluematt.me",
    "seed.bitcoin.jonasschnelli.ch",
    "seed.btc.petertodd.net",
    "seed.bitcoin.sprovoost.nl",
    "dnsseed.emzy.de",
    "seed.bitcoin.wiz.biz",
)

private fun <T> shuffleInPlace(items: MutableList<T>, random: () -> Double): MutableList<T> {
    for (i in items.lastIndex downTo 1) {
        val j = floor(random() * (i + 1)).toInt().coerceIn(0, i)
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
    }
    return items
}

private suspend fun resolveFamily(
    timeoutMs: Long,
    task: suspend () -> List<String>,
): List<String> {
    val run = suspend {
        try {
            task()
        } catch (_: Throwable) {
            emptyList()
        }
    }
    if (timeoutMs <= 0L) return run()
    return try {
        withTimeout(timeoutMs) { run() }
    } catch (_: TimeoutCancellationException) {
        emptyList()
    }
}

suspend fun resolveSeedPeers(
    seeds: List<String>,
    port: Int,
    resolver: DnsResolver,
    random: () -> Double = { kotlin.random.Random.nextDouble() },
    timeoutMs: Long = 3_000,
): List<PeerCandidate> = coroutineScope {
    val resolved = seeds.map { seed ->
        async {
            val v4 = async { resolveFamily(timeoutMs) { resolver.resolve4(seed) } }
            val v6 = async { resolveFamily(timeoutMs) { resolver.resolve6(seed) } }
            v4.await() to v6.await()
        }
    }.awaitAll()
    val v4 = mutableListOf<PeerCandidate>()
    val v6 = mutableListOf<PeerCandidate>()
    for ((a, b) in resolved) {
        for (host in a) v4.add(PeerCandidate(host, port, 0uL))
        for (host in b) v6.add(PeerCandidate(host, port, 0uL))
    }
    shuffleInPlace(v4, random) + shuffleInPlace(v6, random)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.DnsSeedsTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add peers
git commit -m "$(cat <<'EOF'
Port helix3 DNS seed resolution into :peers.

EOF
)"
```

Skip unless the user asked.

---

### Task 3: Module types and detachLoop

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/Types.kt`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/DetachLoop.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/modules/DetachLoopTest.kt`

**Interfaces:**
- Consumes: `:bus` `MessageBus`, `Event.ModuleStatus`, `ModuleStatusPayload`, `ModuleStatus`; `:storage` `Database`
- Produces: `data class ModuleContext(val bus: MessageBus, val db: Database)`; `interface Module { val name: String; suspend fun start(); fun stop() }`; `fun detachLoop(ctx: ModuleContext, module: String, task: Job)`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class DetachLoopTest {
    @Test
    fun emits_module_status_error_when_job_fails() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val seen = mutableListOf<ModuleStatusPayload>()
        bus.on(Event.ModuleStatus) { seen.add(it) }
        val job = launch { error("boom") }
        detachLoop(ModuleContext(bus, db), "peers-discovery", job)
        job.join()
        assertTrue(
            seen.any {
                it.module == "peers-discovery" &&
                    it.status == ModuleStatus.ERROR &&
                    (it.detail ?: "").contains("boom")
            },
        )
        db.close()
    }

    @Test
    fun cancellation_does_not_emit_error() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        val seen = mutableListOf<ModuleStatusPayload>()
        bus.on(Event.ModuleStatus) { seen.add(it) }
        val job = launch { kotlinx.coroutines.delay(60_000) }
        detachLoop(ModuleContext(bus, db), "peers-discovery", job)
        job.cancel()
        job.join()
        assertTrue(seen.none { it.status == ModuleStatus.ERROR })
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.modules.DetachLoopTest`

Expected: FAIL — unresolved `detachLoop` / `ModuleContext`.

- [ ] **Step 3: Implement types and detachLoop**

```kotlin
package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.storage.Database

data class ModuleContext(
    val bus: MessageBus,
    val db: Database,
)

interface Module {
    val name: String
    suspend fun start()
    fun stop()
}
```

```kotlin
package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.peers.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

fun detachLoop(ctx: ModuleContext, module: String, task: Job) {
    task.invokeOnCompletion { err ->
        if (err == null || err is CancellationException) return@invokeOnCompletion
        val detail = err.message ?: err.toString()
        ctx.bus.emit(
            Event.ModuleStatus,
            ModuleStatusPayload(module = module, status = ModuleStatus.ERROR, detail = detail),
        )
        logError(module, "background loop failed", err)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.modules.DetachLoopTest`

Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 4: probePeer

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/UserAgent.kt`
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/PeerProbe.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/StubPlatformNet.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/PeerProbeTest.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/PeerProbeServicesTest.kt`

**Interfaces:**
- Consumes: `TcpConnect`, `PeerCandidate`, `Config.peerProbeTimeoutMs`, bip324 `Protocol`, `ProtocolOptions`, `Role.Initiator`, `Networks.mainnet`, `completeVersionHandshake`, `VersionHandshakeOptions`, `ByteDuplex`, `pairedByteDuplexes`
- Produces: `const val APP_NAME = "blueberry"`; `const val APP_VERSION = "2026.08.17"`; `sealed class ProbeResult { class Ok(val peers: List<PeerCandidate>, val services: ULong); class Err(val error: String) }`; `data class HandshakeResult(val peers: List<PeerCandidate>, val services: ULong)`; `class ProbeOptions(val timeoutMs: Long? = null, val connect: TcpConnect, val handshakeAndGetAddr: (suspend (ByteDuplex, Int) -> HandshakeResult)? = null)`; `suspend fun probePeer(host: String, port: Int, options: ProbeOptions): ProbeResult`

- [ ] **Step 1: Write the failing tests**

`StubPlatformNet.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex

fun stubDuplex(): ByteDuplex = object : ByteDuplex {
    override suspend fun read(n: Int): ByteArray = ByteArray(0)
    override suspend fun write(bytes: ByteArray) {}
    override suspend fun close() {}
}

fun stubPlatformNet(): PlatformNet = PlatformNet(
    connect = { _, _ -> error("stub PlatformNet.connect unused") },
    dns = object : DnsResolver {
        override suspend fun resolve4(host: String) = emptyList<String>()
        override suspend fun resolve6(host: String) = emptyList<String>()
    },
)
```

`PeerProbeTest.kt` (helix3 `peer-probe.test.ts`):

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.Message
import io.bluewallet.bip324.NetworkAddress
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionPayload
import io.bluewallet.bip324.pairedByteDuplexes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerProbeTest {
    @Test
    fun maps_connect_failure_to_err() = runBlocking {
        val result = probePeer(
            "1.2.3.4",
            8333,
            ProbeOptions(
                timeoutMs = 1000,
                connect = { _, _ -> error("ECONNREFUSED") },
                handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
            ),
        )
        assertTrue(result is ProbeResult.Err)
        assertTrue((result as ProbeResult.Err).error.contains("ECONNREFUSED"))
    }

    @Test
    fun timeout_aborts_slow_connect_and_closes_duplex() = runBlocking {
        var closed = false
        val result = probePeer(
            "1.2.3.4",
            8333,
            ProbeOptions(
                timeoutMs = 20,
                connect = { _, _ ->
                    delay(200)
                    val inner = stubDuplex()
                    object : io.bluewallet.bip324.ByteDuplex {
                        override suspend fun read(n: Int) = inner.read(n)
                        override suspend fun write(bytes: ByteArray) = inner.write(bytes)
                        override suspend fun close() {
                            closed = true
                            inner.close()
                        }
                    }
                },
                handshakeAndGetAddr = { _, _ -> HandshakeResult(emptyList(), 0uL) },
            ),
        )
        assertTrue(result is ProbeResult.Err)
        assertTrue(
            (result as ProbeResult.Err).error.contains("timed out") ||
                result.error.contains("aborted"),
        )
        delay(250)
        assertTrue(closed)
    }

    @Test
    fun succeeds_after_verack_without_waiting_for_getaddr() = runBlocking {
        coroutineScope {
            val (clientSide, serverSide) = pairedByteDuplexes()
            val server = async {
                val protocol = Protocol.connect(
                    serverSide,
                    ProtocolOptions(role = Role.Responder, network = Networks.mainnet),
                )
                val version = protocol.readMessage()
                check(version is Message.Version)
                protocol.writeMessage(
                    Message.Version(
                        VersionPayload(
                            version = 70_016,
                            services = 1033uL,
                            timestamp = 0,
                            receiver = NetworkAddress(0uL, ByteArray(16), 8333),
                            sender = NetworkAddress(0uL, ByteArray(16), 0),
                            nonce = 1uL,
                            userAgent = "/test/",
                            startHeight = 0,
                            relay = false,
                        ),
                    ),
                )
                protocol.writeMessage(Message.Verack)
                while (true) {
                    val msg = protocol.readMessage()
                    if (msg.command == "verack") break
                }
                delay(50)
                protocol.close()
            }
            val result = probePeer(
                "127.0.0.1",
                8333,
                ProbeOptions(
                    timeoutMs = 2_000,
                    connect = { _, _ -> clientSide },
                ),
            )
            assertTrue(result is ProbeResult.Ok)
            val ok = result as ProbeResult.Ok
            assertEquals(emptyList(), ok.peers)
            assertEquals(1033uL, ok.services)
            server.await()
        }
    }
}
```

`PeerProbeServicesTest.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerProbeServicesTest {
    @Test
    fun returns_services_from_injected_handshake() = runBlocking {
        val result = probePeer(
            "1.2.3.4",
            8333,
            ProbeOptions(
                timeoutMs = 500,
                connect = { _, _ -> stubDuplex() },
                handshakeAndGetAddr = { _, _ ->
                    HandshakeResult(emptyList(), NODE_COMPACT_FILTERS.toULong())
                },
            ),
        )
        assertTrue(result is ProbeResult.Ok)
        assertEquals(NODE_COMPACT_FILTERS.toULong(), (result as ProbeResult.Ok).services)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.PeerProbeTest --tests io.bluewallet.blueberry.peers.net.PeerProbeServicesTest`

Expected: FAIL — unresolved `probePeer`.

- [ ] **Step 3: Implement probePeer**

`UserAgent.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

const val APP_NAME = "blueberry"
const val APP_VERSION = "2026.08.17"
```

`PeerProbe.kt` port helix3 `peer-probe.ts`:

- `withTimeout(timeoutMs ?: Config.peerProbeTimeoutMs)` around connect + handshake.
- Default handshake: `Protocol.connect(duplex, ProtocolOptions(Role.Initiator, Networks.mainnet))` then `completeVersionHandshake(protocol, VersionHandshakeOptions(port, APP_NAME, APP_VERSION))`. Return `HandshakeResult(emptyList(), services)`. Do not wait for addr.
- `connectOrAbort`: if the timeout/parent cancels during `connect`, close a duplex that still arrives (`invokeOnCompletion` on the connect job).
- `TimeoutCancellationException` → `ProbeResult.Err("probe timed out after ${timeoutMs}ms")`.
- Other throws (except non-timeout `CancellationException` from the parent after stop — convert to `Err` with message, helix3 does not rethrow).
- `finally`: `duplex?.close()` ignoring errors.

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import io.bluewallet.bip324.Networks
import io.bluewallet.bip324.Protocol
import io.bluewallet.bip324.ProtocolOptions
import io.bluewallet.bip324.Role
import io.bluewallet.bip324.VersionHandshakeOptions
import io.bluewallet.bip324.completeVersionHandshake
import io.bluewallet.blueberry.peers.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

private suspend fun defaultHandshakeAndGetAddr(duplex: ByteDuplex, port: Int): HandshakeResult {
    val protocol = Protocol.connect(
        duplex,
        ProtocolOptions(role = Role.Initiator, network = Networks.mainnet),
    )
    val result = completeVersionHandshake(
        protocol,
        VersionHandshakeOptions(port = port, name = APP_NAME, version = APP_VERSION),
    )
    return HandshakeResult(emptyList(), result.services)
}

private suspend fun connectOrAbort(
    connect: TcpConnect,
    host: String,
    port: Int,
): ByteDuplex {
    val scope = CoroutineScope(coroutineContext)
    val pending = CompletableDeferred<ByteDuplex>()
    val connectJob = scope.launch {
        try {
            pending.complete(connect(host, port))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            pending.completeExceptionally(e)
        }
    }
    try {
        return pending.await()
    } catch (e: CancellationException) {
        connectJob.invokeOnCompletion {
            val d = runCatching { pending.getCompleted() }.getOrNull() ?: return@invokeOnCompletion
            scope.launch { runCatching { d.close() } }
        }
        connectJob.cancel()
        throw e
    }
}

suspend fun probePeer(host: String, port: Int, options: ProbeOptions): ProbeResult {
    val timeoutMs = options.timeoutMs ?: Config.peerProbeTimeoutMs
    val handshake = options.handshakeAndGetAddr ?: { d, p -> defaultHandshakeAndGetAddr(d, p) }
    var duplex: ByteDuplex? = null
    return try {
        withTimeout(timeoutMs) {
            duplex = connectOrAbort(options.connect, host, port)
            val hs = handshake(duplex!!, port)
            ProbeResult.Ok(hs.peers, hs.services)
        }
    } catch (e: TimeoutCancellationException) {
        ProbeResult.Err("probe timed out after ${timeoutMs}ms")
    } catch (e: Throwable) {
        ProbeResult.Err(e.message ?: e.toString())
    } finally {
        try {
            duplex?.close()
        } catch (_: Throwable) {
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.PeerProbeTest --tests io.bluewallet.blueberry.peers.net.PeerProbeServicesTest`

Expected: PASS. If the timeout test does not close the late duplex, fix `connectOrAbort` until it matches helix3.

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 5: createPeersDiscoveryModule

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscovery.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/WaitFor.kt`
- Test: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscoveryTest.kt`

**Interfaces:**
- Consumes: `Module`, `ModuleContext`, `detachLoop`, `PlatformNet`, `PeerCandidate`, `ProbeResult`, `probePeer`, `resolveSeedPeers`, `MAINNET_DNS_SEEDS`, `Config`, `currentTimeMillis`, `log`/`logError`, `:storage` `PeerWrite` / `peers.*`, `:bus` `Event.ModuleStatus` `Event.PeersUpdated` `Event.PeersSockets` `Event.SyncIdle` `Event.SyncCatchup`, `PeerSocketKind.PROBE`, `NODE_COMPACT_FILTERS`, `Networks.mainnet.defaultPort`
- Produces: `class PeersDiscoveryOptions(val net: PlatformNet, val resolveSeeds: (suspend () -> List<PeerCandidate>)? = null, val probe: (suspend (String, Int) -> ProbeResult)? = null, val concurrency: Int? = null, val idleDelayMs: Long? = null, val probeTimeoutMs: Long? = null, val now: (() -> Long)? = null, val minAliveCompactFilters: Int? = null, val reseedIntervalMs: Long? = null)`; `fun createPeersDiscoveryModule(ctx: ModuleContext, options: PeersDiscoveryOptions): Module` with `name == "peers-discovery"`

- [ ] **Step 1: Write the failing tests**

`WaitFor.kt`:

```kotlin
package io.bluewallet.blueberry.peers

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

suspend fun waitFor(timeoutMs: Long = 2000, predicate: () -> Boolean) {
    try {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    } catch (_: TimeoutCancellationException) {
        error("timeout waiting for condition")
    }
}
```

Create `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscoveryTest.kt` by translating `/home/ghost/Documents/Blueberry/tests/unit/peers-discovery.test.ts`. Omit only `logs DNS seed count` and `logs probe fail with host`. Keep every other test’s assertions.

Harness (put at the top of that file):

```kotlin
package io.bluewallet.blueberry.peers.modules

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.SyncCatchupPayload
import io.bluewallet.blueberry.bus.SyncCatchupReason
import io.bluewallet.blueberry.bus.SyncIdlePayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.net.PeerCandidate
import io.bluewallet.blueberry.peers.net.ProbeResult
import io.bluewallet.blueberry.peers.net.stubPlatformNet
import io.bluewallet.blueberry.peers.waitFor
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun peer(
    host: String,
    services: ULong = 0uL,
    alive: Boolean = false,
    lastProbedAt: Long? = null,
) = PeerWrite(host, 8333, services, alive, false, lastProbedAt)

private fun hangingSeeds(): suspend () -> List<PeerCandidate> = {
    CompletableDeferred<List<PeerCandidate>>().await()
}
```

Translation rules: `createMessageBus` / `createSqliteDatabase(":memory:")` / `createPeersDiscoveryModule(ModuleContext(bus, db), PeersDiscoveryOptions(...))` / `runBlocking` / `waitFor { }` / `mod.start()` / `mod.stop()` / `db.close()`. `0n` → `0uL`. `ok: true` → `ProbeResult.Ok`. `ok: false, error:` → `ProbeResult.Err`. `bus.emit("sync:idle", { at })` → `bus.emit(Event.SyncIdle, SyncIdlePayload(at))`. `bus.emit("sync:catchup", { at, reason: "headers" })` → `bus.emit(Event.SyncCatchup, SyncCatchupPayload(at, SyncCatchupReason.HEADERS))`. `bus.on("peers:sockets")` → `bus.on(Event.PeersSockets)`. `Date.now()` for `lastProbedAt` in the pause test → a recent `now()` value so that peer is not immediately due (`probeTimeoutMs = 60_000`).

The first test in that file must be:

```kotlin
    @Test
    fun emits_peers_sockets_probe_counts_while_probing() = runBlocking {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(peer("1.1.1.1", alive = true))
        val opens = mutableListOf<Int>()
        bus.on(Event.PeersSockets) { if (it.kind == PeerSocketKind.PROBE) opens.add(it.open) }
        val gate = CompletableDeferred<Unit>()
        val mod = createPeersDiscoveryModule(
            ModuleContext(bus, db),
            PeersDiscoveryOptions(
                net = stubPlatformNet(),
                resolveSeeds = { emptyList() },
                probe = { _, _ ->
                    gate.await()
                    ProbeResult.Err("skip")
                },
                concurrency = 1,
                idleDelayMs = 50,
                minAliveCompactFilters = 0,
            ),
        )
        mod.start()
        waitFor { opens.contains(1) }
        gate.complete(Unit)
        waitFor { opens.contains(0) && opens.indexOf(0) > opens.indexOf(1) }
        mod.stop()
        db.close()
    }
```

Then add the other helix3 behaviour tests in the same class, same order as the TypeScript file, using that harness. Do not invent extra tests. Do not drop assertions except file-log `expect(text).toContain`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.modules.PeersDiscoveryTest`

Expected: FAIL — unresolved `createPeersDiscoveryModule`.

- [ ] **Step 3: Implement the module**

Create `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/modules/PeersDiscovery.kt` as a line-by-line Kotlin port of `/home/ghost/Documents/Blueberry/src/modules/peers-discovery.ts`. Keep control flow, defaults, SQL writes, and events identical. Coroutine stand-ins: `void fn()` → `scope.launch { runCatching { fn() } }`; `waitForKick` → `CompletableDeferred` + `delay`; `setTimeout(0)` → `yield()`; `Date.now` → `options.now ?: { currentTimeMillis() }`.

```kotlin
package io.bluewallet.blueberry.peers.modules

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip324.Networks
import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.peers.Config
import io.bluewallet.blueberry.peers.currentTimeMillis
import io.bluewallet.blueberry.peers.log
import io.bluewallet.blueberry.peers.logError
import io.bluewallet.blueberry.peers.net.MAINNET_DNS_SEEDS
import io.bluewallet.blueberry.peers.net.PeerCandidate
import io.bluewallet.blueberry.peers.net.PlatformNet
import io.bluewallet.blueberry.peers.net.ProbeOptions
import io.bluewallet.blueberry.peers.net.ProbeResult
import io.bluewallet.blueberry.peers.net.probePeer
import io.bluewallet.blueberry.peers.net.resolveSeedPeers
import io.bluewallet.blueberry.storage.PeerWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.math.ceil

class PeersDiscoveryOptions(
    val net: PlatformNet,
    val resolveSeeds: (suspend () -> List<PeerCandidate>)? = null,
    val probe: (suspend (String, Int) -> ProbeResult)? = null,
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
): Module {
    val port = Networks.mainnet.defaultPort
    val resolveSeeds = options.resolveSeeds ?: {
        resolveSeedPeers(MAINNET_DNS_SEEDS, port, options.net.dns)
    }
    val probeTimeoutMs = options.probeTimeoutMs ?: Config.peerProbeTimeoutMs
    val probe = options.probe ?: { host, p ->
        probePeer(host, p, ProbeOptions(timeoutMs = probeTimeoutMs, connect = options.net.connect))
    }
    val concurrency = options.concurrency ?: Config.peerConcurrency
    val idleDelayMs = options.idleDelayMs ?: 500L
    val now = options.now ?: { currentTimeMillis() }
    val minAliveCompactFilters = options.minAliveCompactFilters ?: 16
    val reseedIntervalMs = options.reseedIntervalMs ?: 60_000L

    @Volatile var stopped = true
    var paused = false
    var syncIdle = false
    var unsubIdle: (() -> Unit)? = null
    var unsubCatchup: (() -> Unit)? = null
    var unsubPeers: (() -> Unit)? = null
    var wake: (() -> Unit)? = null
    var lastReseedAt = 0L
    var dnsInFlight = false
    val inflight = mutableSetOf<String>()
    var loopJob: SupervisorJob? = null
    var scope: CoroutineScope? = null

    fun kick() {
        wake?.invoke()
    }

    fun refreshPause() {
        val wantPause = syncIdle && ctx.db.peers.listAlive().isNotEmpty()
        if (wantPause == paused) return
        paused = wantPause
        log("peers-discovery", if (paused) "pause" else "resume")
        kick()
    }

    fun emitUpdated() {
        ctx.bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = now()))
    }

    fun emitSockets() {
        ctx.bus.emit(
            Event.PeersSockets,
            PeersSocketsPayload(at = now(), kind = PeerSocketKind.PROBE, open = inflight.size),
        )
    }

    fun upsertCandidate(candidate: PeerCandidate) {
        ctx.db.peers.upsert(
            PeerWrite(
                host = candidate.host,
                port = candidate.port,
                services = candidate.services,
                alive = false,
                usedForBlocks = false,
                lastProbedAt = null,
            ),
        )
    }

    suspend fun waitForKick(ms: Long) {
        if (stopped) return
        val done = CompletableDeferred<Unit>()
        var settled = false
        fun complete() {
            if (settled) return
            settled = true
            if (wake === ::complete) wake = null
            done.complete(Unit)
        }
        val timer = scope!!.launch {
            delay(ms)
            complete()
        }
        wake = { timer.cancel(); complete() }
        done.await()
    }

    suspend fun pullSeeds() {
        if (dnsInFlight) return
        dnsInFlight = true
        try {
            val seeds = resolveSeeds()
            if (stopped || paused) return
            log("peers-discovery", "dns seeds=${seeds.size}")
            for (candidate in seeds) upsertCandidate(candidate)
            if (seeds.isNotEmpty()) {
                emitUpdated()
                kick()
            }
        } catch (err: Throwable) {
            logError("peers-discovery", "dns", err)
        } finally {
            lastReseedAt = now()
            dnsInFlight = false
        }
    }

    suspend fun bootstrap() {
        if (ctx.db.peers.listAlive().isNotEmpty()) return
        pullSeeds()
    }

    fun aliveCompactFilterCount(): Int =
        ctx.db.peers.listAliveWithServices(NODE_COMPACT_FILTERS.toULong(), minAliveCompactFilters).size

    suspend fun maybeReseed() {
        if (dnsInFlight) return
        if (now() - lastReseedAt < reseedIntervalMs) return
        if (aliveCompactFilterCount() >= minAliveCompactFilters) return
        pullSeeds()
    }

    fun takeProbeBatch(limit: Int): List<Pair<String, Int>> {
        if (limit <= 0) return emptyList()
        val picked = mutableListOf<Pair<String, Int>>()
        val seen = inflight.toMutableSet()
        val t = now()
        fun due(lastProbedAt: Long?) = lastProbedAt == null || t - lastProbedAt >= probeTimeoutMs
        fun take(peers: List<io.bluewallet.blueberry.storage.Peer>, max: Int = limit) {
            for (peer in peers) {
                if (picked.size >= max) return
                val key = "${peer.host}:${peer.port}"
                if (key in seen) continue
                seen.add(key)
                picked.add(peer.host to peer.port)
            }
        }
        if (aliveCompactFilterCount() < minAliveCompactFilters) {
            val cfMax = if (limit < 2) limit else ceil(limit / 2.0).toInt()
            take(
                ctx.db.peers.listWithServices(
                    NODE_COMPACT_FILTERS.toULong(),
                    minAliveCompactFilters + concurrency + 32,
                ).filter { !it.alive && due(it.lastProbedAt) },
                cfMax,
            )
        }
        if (picked.size < limit) {
            take(
                ctx.db.peers.listProbeQueue(concurrency + inflight.size + 16)
                    .filter { due(it.lastProbedAt) },
            )
        }
        return picked
    }

    suspend fun runLoop() {
        while (!stopped) {
            if (paused) {
                waitForKick(60_000)
                continue
            }
            scope!!.launch { runCatching { maybeReseed() } }
            val batch = takeProbeBatch(concurrency - inflight.size)
            var spawned = 0
            for (next in batch) {
                if (stopped || paused) break
                val key = "${next.first}:${next.second}"
                inflight.add(key)
                spawned++
                scope!!.launch {
                    try {
                        val result = probe(next.first, next.second)
                        if (stopped) return@launch
                        ctx.db.peers.markProbed(next.first, next.second, now())
                        if (result is ProbeResult.Ok) {
                            ctx.db.peers.upsert(
                                PeerWrite(
                                    host = next.first,
                                    port = next.second,
                                    services = result.services,
                                    alive = true,
                                    usedForBlocks = false,
                                    lastProbedAt = now(),
                                ),
                            )
                            for (p in result.peers) upsertCandidate(p)
                            ctx.db.peers.markAlive(next.first, next.second, true)
                        } else {
                            val err = (result as ProbeResult.Err).error
                            log("peers-discovery", "probe fail $key error=$err")
                            ctx.db.peers.markAlive(next.first, next.second, false)
                        }
                        emitUpdated()
                    } catch (err: CancellationException) {
                        if (stopped) return@launch
                        throw err
                    } catch (err: Throwable) {
                        if (stopped) return@launch
                        logError("peers-discovery", "probe fail $key", err)
                        ctx.db.peers.markProbed(next.first, next.second, now())
                        ctx.db.peers.markAlive(next.first, next.second, false)
                        emitUpdated()
                    } finally {
                        if (stopped) return@launch
                        inflight.remove(key)
                        emitSockets()
                        kick()
                    }
                }
            }
            if (spawned > 0) emitSockets()
            if (stopped) break
            if (inflight.size >= concurrency || spawned == 0) {
                waitForKick(if (inflight.isNotEmpty()) probeTimeoutMs else idleDelayMs)
            } else {
                waitForKick(1)
            }
            yield()
        }
    }

    return object : Module {
        override val name: String = "peers-discovery"

        override suspend fun start() {
            if (!stopped) return
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.STARTING),
            )
            log("peers-discovery", "start")
            stopped = false
            loopJob = SupervisorJob()
            val moduleScope = CoroutineScope(loopJob!! + Dispatchers.Default)
            scope = moduleScope
            unsubIdle = ctx.bus.on(Event.SyncIdle) {
                syncIdle = true
                refreshPause()
            }
            unsubCatchup = ctx.bus.on(Event.SyncCatchup) {
                syncIdle = false
                refreshPause()
            }
            unsubPeers = ctx.bus.on(Event.PeersUpdated) {
                if (syncIdle) refreshPause()
            }
            moduleScope.launch { runCatching { bootstrap() } }
            detachLoop(ctx, "peers-discovery", moduleScope.launch { runLoop() })
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.RUNNING),
            )
        }

        override fun stop() {
            if (stopped) return
            stopped = true
            log("peers-discovery", "stop")
            unsubIdle?.invoke()
            unsubCatchup?.invoke()
            unsubPeers?.invoke()
            paused = false
            syncIdle = false
            kick()
            inflight.clear()
            emitSockets()
            ctx.bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(module = "peers-discovery", status = ModuleStatus.STOPPED),
            )
            val toCancel = loopJob
            loopJob = null
            scope = null
            toCancel?.cancel()
        }
    }
}
```

Hold `var loopJob: SupervisorJob? = null`. In `start()`, `loopJob = SupervisorJob()` then `scope = CoroutineScope(loopJob!! + Dispatchers.Default)`. `stop()` must set `stopped`, unsubscribe, kick, clear inflight, emit sockets/stopped, **then** `loopJob?.cancel()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.modules.PeersDiscoveryTest :peers:testAndroidHostTest`

Expected: PASS. If a waitFor times out, fix the loop (kick on probe finally, bootstrap not blocking start, pause condition), not the test.

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 6: PlatformNet Android + JVM

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.kt`
- Create: `peers/src/jvmMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.jvm.kt`
- Create: `peers/src/androidMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.android.kt`
- Test: `peers/src/jvmTest/kotlin/io/bluewallet/blueberry/peers/net/PlatformNetJvmTest.kt`

**Interfaces:**
- Consumes: `PlatformNet`, `DnsResolver`, `TcpConnect`, `ByteDuplex`
- Produces: `expect fun createPlatformNet(): PlatformNet` with JVM/Android actuals using `InetAddress.getAllByName` (filter `Inet4Address`/`Inet6Address`) and `java.net.Socket` wrapped as `ByteDuplex` on `Dispatchers.IO` via `runInterruptible`

- [ ] **Step 1: Write the failing localhost test**

`peers/src/jvmTest/kotlin/io/bluewallet/blueberry/peers/net/PlatformNetJvmTest.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class PlatformNetJvmTest {
    @Test
    fun connect_round_trips_bytes_on_localhost() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverJob = launch(Dispatchers.IO) {
            server.use { ss ->
                ss.accept().use { sock ->
                    val buf = ByteArray(4)
                    var n = 0
                    while (n < 4) {
                        val r = sock.getInputStream().read(buf, n, 4 - n)
                        if (r < 0) break
                        n += r
                    }
                    sock.getOutputStream().write(buf)
                    sock.getOutputStream().flush()
                }
            }
        }
        val net = createPlatformNet()
        val duplex = net.connect("127.0.0.1", port)
        duplex.write(byteArrayOf(1, 2, 3, 4))
        val got = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val chunk = duplex.read(4 - offset)
            check(chunk.isNotEmpty())
            chunk.copyInto(got, offset)
            offset += chunk.size
        }
        duplex.close()
        serverJob.join()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), got)
    }

    @Test
    fun dns_resolve4_localhost_is_loopback() = runBlocking {
        val net = createPlatformNet()
        val v4 = net.dns.resolve4("localhost")
        assertTrue(v4.contains("127.0.0.1") || v4.any { it.startsWith("127.") })
    }
}
```

Localhost DNS/TCP is not mainnet. Do not resolve `seed.bitcoin.sipa.be` here.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.PlatformNetJvmTest`

Expected: FAIL — unresolved `createPlatformNet`.

- [ ] **Step 3: Implement expect + JVM/Android actuals**

`PlatformNet.kt`:

```kotlin
package io.bluewallet.blueberry.peers.net

expect fun createPlatformNet(): PlatformNet
```

JVM and Android actual files must contain the **same** implementation:

```kotlin
package io.bluewallet.blueberry.peers.net

import io.bluewallet.bip324.ByteDuplex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

actual fun createPlatformNet(): PlatformNet = PlatformNet(
    connect = { host, port -> connectSocket(host, port) },
    dns = object : DnsResolver {
        override suspend fun resolve4(host: String): List<String> = withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().map { it.hostAddress }
        }
        override suspend fun resolve6(host: String): List<String> = withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).filterIsInstance<Inet6Address>().map { it.hostAddress }
        }
    },
)
```

For IPv6 `hostAddress`, use `Inet6Address.hostAddress` as returned (no extra trim hack). If Java appends `%scope`, keep it; `InetSocketAddress` accepts it.

`connectSocket`:

```kotlin
private suspend fun connectSocket(host: String, port: Int): ByteDuplex {
    val socket = Socket()
    try {
        runInterruptible(Dispatchers.IO) {
            socket.connect(InetSocketAddress(host, port))
            socket.tcpNoDelay = true
        }
    } catch (e: Throwable) {
        runCatching { socket.close() }
        throw e
    }
    return SocketByteDuplex(socket)
}

private class SocketByteDuplex(private val socket: Socket) : ByteDuplex {
    private val mutex = Mutex()
    override suspend fun read(n: Int): ByteArray = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            if (socket.isClosed) ByteArray(0)
            else {
                val buf = ByteArray(n)
                val r = socket.getInputStream().read(buf)
                if (r <= 0) ByteArray(0) else buf.copyOf(r)
            }
        }
    }
    override suspend fun write(bytes: ByteArray) = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }
    override suspend fun close() = mutex.withLock {
        runInterruptible(Dispatchers.IO) { socket.close() }
    }
}
```

Copy that entire actual file to both `PlatformNet.jvm.kt` and `PlatformNet.android.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.PlatformNetJvmTest`

Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 7: PlatformNet iOS

**Files:**
- Create: `peers/src/iosMain/kotlin/io/bluewallet/blueberry/peers/net/PlatformNet.ios.kt`

**Interfaces:**
- Consumes: `expect fun createPlatformNet()`
- Produces: iOS actual using `getaddrinfo` (`AF_INET` / `AF_INET6`) and POSIX `socket`/`connect`/`read`/`write`/`close`, `ByteDuplex` on `Dispatchers.IO`

- [ ] **Step 1: Write a compile-only check**

No iOS simulator tests on Linux. The failing signal is:

Run: `./gradlew :peers:compileKotlinIosArm64`

Expected: FAIL — missing iOS actual for `createPlatformNet`.

- [ ] **Step 2: Confirm it fails for the right reason**

The linker/compiler error must mention `createPlatformNet` actual missing, not an unrelated vendor error.

- [ ] **Step 3: Implement POSIX actual**

`PlatformNet.ios.kt`:

- `resolve(host, family)`: `getaddrinfo` with `hints.ai_family = family`, `ai_socktype = SOCK_STREAM`, walk `ai_next`, `inet_ntop` into a buffer, `freeaddrinfo`. On non-zero `getaddrinfo`, return `emptyList()`.
- `connect(host, port)`: `getaddrinfo` with `AI_UNSPEC` and port string; `socket`; `connect`; on failure `close` and throw `IllegalStateException` with `strerror`. Wrap fd in `PosixByteDuplex`: Mutex; `read`/`write`/`close` via `platform.posix.read`/`write`/`close` inside `withContext(Dispatchers.IO)`; `close` idempotent; empty read at EOF (`n==0`) or after close.
- Use `@OptIn(ExperimentalForeignApi::class)`.
- IPv4 and IPv6 both required.

- [ ] **Step 4: Compile iOS**

Run: `./gradlew :peers:compileKotlinIosArm64`

Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 8: PeerSocketsStore

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeerSocketsStore.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeerSocketsStoreTest.kt`
- Modify: `shared/build.gradle.kts` only if a new test dep is required (it already has `:bus` and `:storage`)

**Interfaces:**
- Consumes: `PeerSocketKind`, `createMessageBus`, `Event.PeersUpdated`, `Event.PeersSockets`, `createSqliteDatabase`, `PeerWrite`
- Produces: `data class PeerSocketCounts(val known: Int = 0, val probe: Int = 0, val hdr: Int = 0, val filt: Int = 0, val blk: Int = 0)`; `interface PeerSocketsStore { fun get(): PeerSocketCounts; fun setKnown(known: Int); fun applyEvent(kind: PeerSocketKind, open: Int); fun subscribe(listener: () -> Unit): () -> Unit }`; `fun createPeerSocketsStore(): PeerSocketsStore`; `fun formatPeerSockets(counts: PeerSocketCounts): String`; `fun hydratePeers(db: Database, store: PeerSocketsStore) { store.setKnown(db.peers.count()) }`

- [ ] **Step 1: Write the failing tests**

Port helix3 `tests/unit/tui-peer-sockets.test.ts`:

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PeerSocketsStoreTest {
    @Test
    fun merges_kinds_independently_clamps_open_ignores_noops() {
        val store = createPeerSocketsStore()
        var ticks = 0
        store.subscribe { ticks++ }
        store.applyEvent(PeerSocketKind.PROBE, 2)
        store.applyEvent(PeerSocketKind.FILT, 4)
        store.applyEvent(PeerSocketKind.PROBE, 0)
        assertEquals(
            PeerSocketCounts(known = 0, probe = 0, hdr = 0, filt = 4, blk = 0),
            store.get(),
        )
        store.applyEvent(PeerSocketKind.BLK, -3)
        assertEquals(0, store.get().blk)
        val before = ticks
        store.applyEvent(PeerSocketKind.FILT, 4)
        assertEquals(before, ticks)
    }

    @Test
    fun format_matches_helix3() {
        assertEquals(
            "probe 1 · hdr 2 · filt 3 · blk 4",
            formatPeerSockets(PeerSocketCounts(known = 9, probe = 1, hdr = 2, filt = 3, blk = 4)),
        )
    }

    @Test
    fun seeds_known_from_db_and_applies_bus_updates() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, 0uL, alive = false, usedForBlocks = false, lastProbedAt = null),
        )
        val store = createPeerSocketsStore()
        val unsubs = mutableListOf<() -> Unit>()
        unsubs += bus.on(Event.PeersUpdated) { hydratePeers(db, store) }
        unsubs += bus.on(Event.PeersSockets) { store.applyEvent(it.kind, it.open) }
        hydratePeers(db, store)
        assertEquals(1, store.get().known)

        bus.emit(Event.PeersSockets, PeersSocketsPayload(at = 1, kind = PeerSocketKind.HDR, open = 3))
        assertEquals(3, store.get().hdr)

        db.peers.upsert(
            PeerWrite("9.9.9.9", 8333, 1uL, alive = false, usedForBlocks = false, lastProbedAt = null),
        )
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(at = 2))
        assertEquals(2, store.get().known)

        unsubs.forEach { it() }
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.PeerSocketsStoreTest`

Expected: FAIL — unresolved `createPeerSocketsStore`.

- [ ] **Step 3: Implement the store**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeerSocketsStore.kt`:

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.storage.Database
import kotlin.math.max

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

fun formatPeerSockets(counts: PeerSocketCounts): String =
    "probe ${counts.probe} · hdr ${counts.hdr} · filt ${counts.filt} · blk ${counts.blk}"

fun hydratePeers(db: Database, store: PeerSocketsStore) {
    store.setKnown(db.peers.count())
}

fun createPeerSocketsStore(): PeerSocketsStore {
    var snapshot = PeerSocketCounts()
    val listeners = mutableSetOf<() -> Unit>()
    fun notify() {
        for (listener in listeners.toList()) listener()
    }
    return object : PeerSocketsStore {
        override fun get() = snapshot
        override fun setKnown(known: Int) {
            val next = max(0, known)
            if (snapshot.known == next) return
            snapshot = snapshot.copy(known = next)
            notify()
        }
        override fun applyEvent(kind: PeerSocketKind, open: Int) {
            val next = max(0, open)
            val cur = when (kind) {
                PeerSocketKind.PROBE -> snapshot.probe
                PeerSocketKind.HDR -> snapshot.hdr
                PeerSocketKind.FILT -> snapshot.filt
                PeerSocketKind.BLK -> snapshot.blk
            }
            if (cur == next) return
            snapshot = when (kind) {
                PeerSocketKind.PROBE -> snapshot.copy(probe = next)
                PeerSocketKind.HDR -> snapshot.copy(hdr = next)
                PeerSocketKind.FILT -> snapshot.copy(filt = next)
                PeerSocketKind.BLK -> snapshot.copy(blk = next)
            }
            notify()
        }
        override fun subscribe(listener: () -> Unit): () -> Unit {
            listeners.add(listener)
            return { listeners.remove(listener) }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.PeerSocketsStoreTest :shared:testAndroidHostTest`

Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 9: App lifecycle, Peers screen, Self-diagnostics, INTERNET

**Files:**
- Modify: `shared/build.gradle.kts` — add `implementation(project(":peers"))`
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt`
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/SettingsScreen.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `createPeersDiscoveryModule`, `PeersDiscoveryOptions`, `ModuleContext`, `createPlatformNet`, `createMessageBus`, `createPeerSocketsStore`, `hydratePeers`, `formatPeerSockets`, `OnboardingGate.Start`
- Produces: discovery running only while gate is `Start`; Peers UI; Settings Self-diagnostics; `android.permission.INTERNET`

- [ ] **Step 1: Write the failing runtime test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeersRuntimeTest.kt`:

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.PeerSocketKind
import io.bluewallet.blueberry.bus.PeersSocketsPayload
import io.bluewallet.blueberry.bus.PeersUpdatedPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.storage.PeerWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class PeersRuntimeTest {
    @Test
    fun bindPeerSocketEvents_hydrates_and_applies() {
        val bus = createMessageBus()
        val db = createSqliteDatabase(":memory:")
        db.peers.upsert(
            PeerWrite("1.1.1.1", 8333, 0uL, false, false, null),
        )
        val store = createPeerSocketsStore()
        val off = bindPeerSocketEvents(bus, db, store)
        hydratePeers(db, store)
        assertEquals(1, store.get().known)
        bus.emit(Event.PeersSockets, PeersSocketsPayload(1, PeerSocketKind.PROBE, 2))
        assertEquals(2, store.get().probe)
        db.peers.upsert(PeerWrite("9.9.9.9", 8333, 0uL, false, false, null))
        bus.emit(Event.PeersUpdated, PeersUpdatedPayload(2))
        assertEquals(2, store.get().known)
        off()
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.PeersRuntimeTest`

Expected: FAIL — unresolved `bindPeerSocketEvents`.

- [ ] **Step 3: Implement runtime, UI, permission**

`PeersRuntime.kt`:

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.blueberry.bus.Event
import io.bluewallet.blueberry.bus.MessageBus
import io.bluewallet.blueberry.bus.ModuleStatus
import io.bluewallet.blueberry.bus.ModuleStatusPayload
import io.bluewallet.blueberry.bus.createMessageBus
import io.bluewallet.blueberry.peers.modules.Module
import io.bluewallet.blueberry.peers.modules.ModuleContext
import io.bluewallet.blueberry.peers.modules.PeersDiscoveryOptions
import io.bluewallet.blueberry.peers.modules.createPeersDiscoveryModule
import io.bluewallet.blueberry.peers.net.createPlatformNet
import io.bluewallet.blueberry.storage.Database

fun bindPeerSocketEvents(bus: MessageBus, db: Database, store: PeerSocketsStore): () -> Unit {
    val a = bus.on(Event.PeersUpdated) { hydratePeers(db, store) }
    val b = bus.on(Event.PeersSockets) { store.applyEvent(it.kind, it.open) }
    return {
        a()
        b()
    }
}

class PeersRuntime(private val db: Database) {
    val bus: MessageBus = createMessageBus()
    val store: PeerSocketsStore = createPeerSocketsStore()
    private val module: Module = createPeersDiscoveryModule(
        ModuleContext(bus, db),
        PeersDiscoveryOptions(net = createPlatformNet()),
    )
    private var unbind: (() -> Unit)? = null

    suspend fun start() {
        unbind = bindPeerSocketEvents(bus, db, store)
        hydratePeers(db, store)
        try {
            module.start()
        } catch (e: Throwable) {
            bus.emit(
                Event.ModuleStatus,
                ModuleStatusPayload(
                    module = "peers-discovery",
                    status = ModuleStatus.ERROR,
                    detail = e.message ?: e.toString(),
                ),
            )
        }
    }

    fun stop() {
        unbind?.invoke()
        unbind = null
        module.stop()
    }
}
```

`PeersScreen.kt`:

```kotlin
package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PeersScreen(store: PeerSocketsStore, onOpenSettings: () -> Unit) {
    var counts by remember { mutableStateOf(store.get()) }
    DisposableEffect(store) {
        val off = store.subscribe { counts = store.get() }
        onDispose { off() }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
    ) {
        Text("Peers")
        Text(formatPeerSockets(counts))
        Text("${counts.known} known")
        Button(onClick = onOpenSettings) { Text("Settings") }
    }
}
```

`ClickMeContent.kt`: change signature to `fun ClickMeContent()` — delete the Settings button and the `onOpenSettings` parameter. Keep the **Click me!** button, logo, and vendor lines.

`SettingsScreen.kt`: after the Clear storage button, add `Text("Self-diagnostics")` then `ClickMeContent()`.

`App.kt` (keep existing DB/gate/settings/onboarding; only change Start + runtime):

```kotlin
val started = gate is OnboardingGate.Start
val runtime = remember(databasePath, session, started) {
    if (started) PeersRuntime(db) else null
}
val scope = rememberCoroutineScope()
DisposableEffect(runtime) {
    val job = scope.launch { runtime?.start() }
    onDispose {
        job.cancel()
        runtime?.stop()
    }
}
if (showSettings) {
    SettingsScreen(
        onClearStorage = {
            opened.close()
            deleteSqliteDatabaseFiles(databasePath)
            showSettings = false
            session += 1
        },
        onBack = { showSettings = false },
    )
    return@MaterialTheme
}
when (val current = gate) {
    is OnboardingGate.Start -> PeersScreen(
        store = checkNotNull(runtime).store,
        onOpenSettings = { showSettings = true },
    )
    is OnboardingGate.ExitInvalid -> InvalidSecretScreen(current.detail)
    is OnboardingGate.Onboard -> OnboardingApp(/* existing persist callbacks */)
}
```

In `shared/build.gradle.kts` `commonMain.dependencies` add `implementation(project(":peers"))`.

`AndroidManifest.xml` inside `<manifest>`, before `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 4: Run tests and compile**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.PeersRuntimeTest :shared:jvmTest :shared:testAndroidHostTest :shared:compileKotlinJvm`

Expected: PASS. Existing onboarding tests still pass. `ClickMeContent` call sites compile.

- [ ] **Step 5: Commit**

Skip unless the user asked.

---

### Task 10: Full verification

**Files:** none new

**Interfaces:**
- Consumes: all previous tasks
- Produces: evidence that spec Success holds

- [ ] **Step 1: Run peers tests**

Run: `./gradlew :peers:jvmTest :peers:testAndroidHostTest`

Expected: PASS, no live DNS seed hostnames in test output.

- [ ] **Step 2: Run shared tests**

Run: `./gradlew :shared:jvmTest :shared:testAndroidHostTest`

Expected: PASS

- [ ] **Step 3: Compile Android, iOS, JVM**

Run: `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :shared:compileKotlinJvm`

Expected: PASS

- [ ] **Step 4: Manual check if a device or desktop is available**

Desktop: `./gradlew :desktopApp:run` with an already-onboarded DB (or complete onboarding). Main screen shows `Peers`, `probe N · hdr 0 · filt 0 · blk 0`, `N known` rising. Settings Self-diagnostics still toggles vendor lines. Discovery does not stop while Settings is open. Clear storage returns to onboarding.

Android: `adb devices`, `./gradlew :androidApp:installDebug`, `adb shell am start -n io.bluewallet.blueberry/.MainActivity`. Same UI. Do not claim the app is running without `adb` evidence.

If neither target can run, say so; unit tests still must have passed.

- [ ] **Step 5: Commit**

Skip unless the user asked.
