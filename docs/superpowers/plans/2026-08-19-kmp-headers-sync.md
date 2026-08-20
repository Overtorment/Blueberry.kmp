# KMP Headers Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port helix3 chain-header sync so it validates and persists headers, and drives the Start screen Chain tip tile plus `hdr` sockets after onboarding.

**Architecture:** `header-sync` and header config live in `:peers/net`. New `:headers` holds checkpoint, trusted-chain, and `createChainHeadersModule`. `:shared` owns the progress store, hydrate, Chain tip UI, and starts the module from `PeersRuntime` after gate `Start`.

**Tech Stack:** Kotlin 2.4.10, kotlinx-coroutines 1.11.0, `:peers`, `:storage`, `:bus`, `:wallet`, `io.bluewallet:bitcoin-headers`, `io.bluewallet:bip324`.

**Spec:** `docs/superpowers/specs/2026-08-19-kmp-headers-sync-design.md`

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-19-kmp-headers-sync-design.md`.
- Behaviour source: `/home/ghost/Documents/Blueberry/src/modules/chain-headers.ts`, `/home/ghost/Documents/Blueberry/src/net/header-sync.ts`, `/home/ghost/Documents/Blueberry/src/checkpoint.ts`, `/home/ghost/Documents/Blueberry/src/headers/trusted-chain.ts`, `/home/ghost/Documents/Blueberry/src/tui/headers-progress-store.ts`, `/home/ghost/Documents/Blueberry/src/tui/hydrate.ts`, `/home/ghost/Documents/Blueberry/src/tui/progress-eta.ts`, `/home/ghost/Documents/Blueberry/src/tui/progress-format.ts`, `/home/ghost/Documents/Blueberry/src/tui/components/ChainTipSync.tsx`.
- Package: `io.bluewallet.blueberry.headers` (+ `modules`). Header-sync stays `io.bluewallet.blueberry.peers.net`. Store/UI stay `io.bluewallet.blueberry`.
- Public names match helix3 exports. Promises → `suspend`. Storage heights `Int`; bitcoin-headers heights `Long`.
- `HeaderBatchResult.headers` is `io.bluewallet.headers.BlockHeader`.
- `log` / `logError` are no-ops. Do not assert `[chain-headers]` log text.
- Default Gradle tests must not call live mainnet `:8333`.
- Do not port filters/blocks/broadcast/sync-idle modules. Idle listeners stay.
- Do not change `:storage`, `:bus`, or `:wallet` public APIs. Do not change Kotlin `2.4.10`.
- Do not add Compose UI tests. Opening Settings must not stop sync.
- Pass `./gradlew :peers:jvmTest :peers:testAndroidHostTest`, `./gradlew :headers:jvmTest :headers:testAndroidHostTest`, `./gradlew :shared:jvmTest :shared:testAndroidHostTest`, `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :headers:compileKotlinJvm :shared:compileKotlinJvm`.
- Do not commit unless the user asks.

## File structure

```
settings.gradle.kts
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/HeaderSync.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/HeaderSyncTest.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/HeaderSessionPoolTest.kt
headers/build.gradle.kts
headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/Checkpoint.kt
headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/TrustedChain.kt
headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/modules/ChainHeaders.kt
headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/CheckpointTest.kt
headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/TrustedChainTest.kt
headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/Mine.kt
headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/WaitFor.kt
headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/modules/ChainHeadersTest.kt
shared/build.gradle.kts
shared/src/commonMain/kotlin/io/bluewallet/blueberry/HeadersProgressStore.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersHydrateTest.kt
```

---

### Task 1: Header Config + `:headers` Gradle module

**Files:**
- Modify: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt`
- Modify: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt`
- Modify: `settings.gradle.kts`
- Create: `headers/build.gradle.kts`
- Modify: `shared/build.gradle.kts`

**Interfaces:**
- Consumes: existing `Config`, `:peers` Gradle pattern
- Produces: `Config.headerSyncTimeoutMs = 30_000L`, `Config.headerRacePeers = 10`; `:headers` module compiles

- [ ] **Step 1: Write the failing test**

Add to `ConfigTest`:

```kotlin
assertEquals(30_000L, Config.headerSyncTimeoutMs)
assertEquals(10, Config.headerRacePeers)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest`
Expected: FAIL — unresolved `headerSyncTimeoutMs`

- [ ] **Step 3: Write minimal implementation**

Add the two constants. Create `:headers` with the same KMP/Android/iOS/JVM shape as `:peers`. Dependencies: `api(project(":peers"))`, `api(project(":storage"))`, `api(project(":bus"))`, `implementation(project(":wallet"))`, `api(libs.bitcoin.headers)`, `implementation(libs.bip324)`, `implementation(libs.kotlinx.coroutines.core)`. iOS `linkerOpts("-lsqlite3")`. `androidHostTest` sqlite-jdbc like `:peers`. `include(":headers")`. `:shared` `implementation(project(":headers"))`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest :headers:compileKotlinJvm :shared:compileKotlinJvm`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 2: Checkpoint

**Files:**
- Create: `headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/Checkpoint.kt`
- Create: `headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/CheckpointTest.kt`

**Interfaces:**
- Consumes: helix3 `CHECKPOINTS` hex table; bitcoin-headers consensus types; storage `HeaderRecord`
- Produces: `checkpointForYear`, `consensusForYear`, `checkpointDbRecord`, `checkpointSeedRecord`, `CHECKPOINT_HEIGHT = 556416`, `BLUEBERRY_HEADER_CONSENSUS`, `DEFAULT_CHECKPOINT_YEAR = 2019`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun year_2019_is_default_height_556416() {
    assertEquals(2019, DEFAULT_CHECKPOINT_YEAR)
    assertEquals(556416, CHECKPOINT_HEIGHT)
    assertEquals(556416, checkpointForYear(2019).height)
    assertEquals(BLUEBERRY_HEADER_CONSENSUS.checkpoint.height, 556416L)
    assertEquals(checkpointDbRecord().height, 556416)
    assertEquals(checkpointSeedRecord().height, 556416)
}

@Test
fun unknown_year_throws() {
    val ex = assertFailsWith<IllegalArgumentException> { checkpointForYear(1999) }
    assertTrue(ex.message!!.contains("unknown checkpoint year: 1999"))
}

@Test
fun every_onboarding_year_has_a_checkpoint() {
    for (year in 2009..2026) {
        val seed = checkpointSeedRecord(year)
        assertEquals(checkpointForYear(year).height, seed.height)
        assertEquals(80, seed.headerHex.length / 2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.CheckpointTest`
Expected: FAIL — unresolved references

- [ ] **Step 3: Write minimal implementation**

Port `/home/ghost/Documents/Blueberry/src/checkpoint.ts` exactly (hex, timestamps, `consensusFrom`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.CheckpointTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 3: Trusted chain

**Files:**
- Create: `headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/TrustedChain.kt`
- Create: `headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/TrustedChainTest.kt`

**Interfaces:**
- Consumes: `StoredHeader`, `HeaderConsensusParams`
- Produces: `TRUSTED_CHAIN_WINDOW = 4096`, `trustedChainFromStored`, `internalHexToDisplayHex`

- [ ] **Step 1: Write the failing test**

Port `trusted-chain.test.ts`: seed checkpoint, `trustedChainFromStored(loadAll())`, tip height/hash/work match, `TRUSTED_CHAIN_WINDOW > 2016`. Also: empty list throws; gap throws.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.TrustedChainTest`
Expected: FAIL — unresolved references

- [ ] **Step 3: Write minimal implementation**

Port `trusted-chain.ts` + `internalHexToDisplayHex` (byte-reverse hex).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.TrustedChainTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 4: Header session pool + one-shot fetch

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/HeaderSync.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/HeaderSyncTest.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/HeaderSessionPoolTest.kt`

**Interfaces:**
- Consumes: `TcpConnect`, `ByteDuplex`, `Config`, `APP_NAME` / `APP_VERSION`
- Produces: `SESSION_BUSY_ERROR`, `HeaderBatchResult`, `createHeaderSessionPool`, `fetchHeadersBatch`

- [ ] **Step 1: Write the failing tests**

Port `header-sync.test.ts` and `header-session-pool.test.ts`. `ok: true/false` → `HeaderBatchResult.Ok` / `Err`. `waitFor` from `:peers` test helpers. `stubDuplex()` already exists.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.HeaderSyncTest --tests io.bluewallet.blueberry.peers.net.HeaderSessionPoolTest`
Expected: FAIL — unresolved references

- [ ] **Step 3: Write minimal implementation**

Port `header-sync.ts`. Convert bip324 `Message.Headers` wire headers to `io.bluewallet.headers.BlockHeader`. Timeouts: `withTimeout`. Reuse `connectOrAbort` pattern from `PeerProbe.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.HeaderSyncTest --tests io.bluewallet.blueberry.peers.net.HeaderSessionPoolTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 5: `createChainHeadersModule`

**Files:**
- Create: `headers/src/commonMain/kotlin/io/bluewallet/blueberry/headers/modules/ChainHeaders.kt`
- Create: `headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/modules/ChainHeadersTest.kt`
- Create: `headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/Mine.kt`
- Create: `headers/src/commonTest/kotlin/io/bluewallet/blueberry/headers/WaitFor.kt`

**Interfaces:**
- Consumes: `ModuleContext`, `HeaderSessionPool` / injected `fetchBatch`, `trustedChainFromStored`, `maybeFreezeWalletBirthday`
- Produces: `createChainHeadersModule(ctx, options): Module` name `chain-headers`

- [ ] **Step 1: Write the failing tests**

Port every behaviour test in `chain-headers.test.ts` except file-log `toContain("[chain-headers]")` assertions. Translation: `0n` → `0uL`, `ok: true` → `HeaderBatchResult.Ok`, `bus.on("headers:progress")` → `bus.on(Event.HeadersProgress)`, `inspectWalletBirthday` sealed types, `waitFor` suspend, `runBlocking`. Real mainnet header at 556417 stays as helix3 hex. Easy-difficulty `mineHeader` / `buildReorgFixture` / `mineEasyChain` in `Mine.kt`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.modules.ChainHeadersTest`
Expected: FAIL — unresolved `createChainHeadersModule`

- [ ] **Step 3: Write minimal implementation**

Port `chain-headers.ts` 1:1. Heights: `Long` inside bitcoin-headers types, `Int` at SQLite/bus. `stop()` joins the loop without cancelling the in-flight fetch.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :headers:jvmTest --tests io.bluewallet.blueberry.headers.modules.ChainHeadersTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 6: Progress store, hydrate, Chain tip UI, runtime

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/HeadersProgressStore.kt`
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ProgressFormat.kt`
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersProgressStoreTest.kt`
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/HeadersHydrateTest.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt`
- Modify: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeersRuntimeTest.kt`

**Interfaces:**
- Consumes: `Event.HeadersProgress`, `loadSyncFromYear`, `consensusForYear`, `createChainHeadersModule`
- Produces: store + hydrate + Start screen Chain tip; runtime starts both modules

- [ ] **Step 1: Write the failing tests**

Port `headers-progress-store.test.ts`. Hydrate: two header rows height 10–11 → downloaded 1, total 1, height 11; emit `total: 0` does not clobber; emit `total: 500` updates total keeps downloaded from DB.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest --tests io.bluewallet.blueberry.HeadersHydrateTest`
Expected: FAIL — unresolved references

- [ ] **Step 3: Write minimal implementation**

Port store/ETA/format. `hydrateHeaders` uses `sessionOrDurableTotal`. Runtime: hydrate peers + headers, bind both event sets, start discovery then chain-headers with `consensusForYear(loadSyncFromYear(db))`. Screen adds Chain tip lines. `formatEta` / `progressBar(width=10)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.HeadersProgressStoreTest --tests io.bluewallet.blueberry.HeadersHydrateTest --tests io.bluewallet.blueberry.PeersRuntimeTest :headers:jvmTest :peers:jvmTest`
Expected: PASS

- [ ] **Step 5: Full verification**

Run the spec Pass commands. Fix any failures.

- [ ] **Step 6: Commit**

Skip unless the user asks.
