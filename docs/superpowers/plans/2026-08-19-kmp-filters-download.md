# KMP Filters Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]` ) syntax for tracking.

**Goal:** Port helix3 compact-filter download so it verifies and persists BIP-157 filters, and drives the Start screen Filters DL tile plus `filt` sockets after onboarding.

**Architecture:** `filter-sync`, `filter-session-pool`, and filter config live in `:peers/net`. New `:filters` holds `createFiltersDownloadModule`. `:shared` owns the progress store, hydrate, Filters DL UI, and starts the module from `PeersRuntime` after gate `Start`.

**Tech Stack:** Kotlin 2.4.10, kotlinx-coroutines 1.11.0, `:peers`, `:storage`, `:bus`, `:wallet`, `io.bluewallet:bip157`, `io.bluewallet:bip324`.

**Spec:** `docs/superpowers/specs/2026-08-19-kmp-filters-download-design.md`

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-19-kmp-filters-download-design.md`.
- Behaviour source: `/home/ghost/Documents/Blueberry/src/modules/filters-download.ts`, `/home/ghost/Documents/Blueberry/src/net/filter-sync.ts`, `/home/ghost/Documents/Blueberry/src/net/filter-session-pool.ts`, `/home/ghost/Documents/Blueberry/src/tui/filters-progress-store.ts`, `/home/ghost/Documents/Blueberry/src/tui/hydrate.ts`, `/home/ghost/Documents/Blueberry/src/tui/components/FiltersDownload.tsx`, `/home/ghost/Documents/Blueberry/tests/unit/filter-sync.test.ts`, `/home/ghost/Documents/Blueberry/tests/unit/filter-session-pool.test.ts`, `/home/ghost/Documents/Blueberry/tests/unit/filters-download.test.ts`, `/home/ghost/Documents/Blueberry/tests/unit/filters-progress-store.test.ts`.
- Package: `io.bluewallet.blueberry.filters` (+ `modules`). Filter-sync stays `io.bluewallet.blueberry.peers.net`. Store/UI stay `io.bluewallet.blueberry`.
- Public names match helix3 exports. Promises → `suspend`. `bigint` services → `ULong`. `ok: true/false` → sealed `Ok` / `Err`.
- `log` / `logError` are no-ops. Do not assert `[filters-download]` log text. Keep injected `log` diagnostics.
- Default Gradle tests must not call live mainnet `:8333`.
- Do not port matching/blocks/broadcast/sync-idle modules. Idle listeners stay.
- Do not change `:storage`, `:bus`, or `:wallet` public APIs. Do not change Kotlin `2.4.10`.
- Do not add Compose UI tests. Opening Settings must not stop sync.
- Pass `./gradlew :peers:jvmTest :peers:testAndroidHostTest`, `./gradlew :filters:jvmTest :filters:testAndroidHostTest`, `./gradlew :shared:jvmTest :shared:testAndroidHostTest`, `./gradlew :peers:compileAndroidMain :peers:compileKotlinIosArm64 :filters:compileKotlinJvm :shared:compileKotlinJvm`.
- Do not commit unless the user asks.

## File structure

```
settings.gradle.kts
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/FilterSync.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/FilterSyncTest.kt
peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/FilterSessionPool.kt
peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/FilterSessionPoolTest.kt
filters/build.gradle.kts
filters/src/commonMain/kotlin/io/bluewallet/blueberry/filters/modules/FiltersDownload.kt
filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/WaitFor.kt
filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/modules/FiltersDownloadTest.kt
shared/build.gradle.kts
shared/src/commonMain/kotlin/io/bluewallet/blueberry/FiltersProgressStore.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersProgressStoreTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersHydrateTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeersRuntimeTest.kt
```

---

### Task 1: Filter Config + `:filters` Gradle module

**Files:**
- Modify: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/Config.kt`
- Modify: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/ConfigTest.kt`
- Modify: `settings.gradle.kts`
- Create: `filters/build.gradle.kts`
- Modify: `shared/build.gradle.kts`

**Interfaces:**
- Consumes: existing `Config`, `:headers` Gradle pattern
- Produces: `Config.filterSyncTimeoutMs = 30_000L`, `Config.filterConcurrency = 10`, `Config.filterHeaderBatchSize = 2000`, `Config.filterBatchSize = 100`; `:filters` module compiles

- [ ] **Step 1: Write the failing test**

Add to `ConfigTest.helix3_probe_defaults`:

```kotlin
assertEquals(30_000L, Config.filterSyncTimeoutMs)
assertEquals(10, Config.filterConcurrency)
assertEquals(2000, Config.filterHeaderBatchSize)
assertEquals(100, Config.filterBatchSize)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest`
Expected: FAIL — unresolved `filterSyncTimeoutMs`

- [ ] **Step 3: Write minimal implementation**

Add the four constants. Create `:filters` with the same KMP/Android/iOS/JVM shape as `:headers`. Dependencies: `api(project(":peers"))`, `api(project(":storage"))`, `api(project(":bus"))`, `implementation(project(":wallet"))`, `api(libs.bip157)`, `implementation(libs.bip324)`, `implementation(libs.kotlinx.coroutines.core)`. iOS `linkerOpts("-lsqlite3")`. `androidHostTest` sqlite-jdbc like `:headers`. `include(":filters")`. `:shared` `implementation(project(":filters"))`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.ConfigTest :filters:compileKotlinJvm :shared:compileKotlinJvm`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 2: `openFilterSession` + inactivity timeout

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/FilterSync.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/FilterSyncTest.kt`

**Interfaces:**
- Consumes: `TcpConnect`, `ByteDuplex`, `Config`, `APP_NAME` / `APP_VERSION`, bip157 `encodeOutbound` / decode / `BIP157_SHORT_IDS`
- Produces: `FilterBatchResult`, `FilterSessionApi`, `CFHeadersResult`, `CFilterItem`, `FilterSyncOptions`, `openFilterSession`, `createInactivityTimeout`, `runWithInactivityTimeout`

- [ ] **Step 1: Write the failing tests**

Port `filter-sync.test.ts`:

```kotlin
@Test
fun maps_connect_failure_to_err() = runBlocking {
    val result = openFilterSession(
        "1.2.3.4",
        8333,
        FilterSyncOptions(
            connectTimeoutMs = 100,
            syncTimeoutMs = 100,
            connect = { _, _ -> error("ECONNREFUSED") },
        ),
    )
    assertTrue(result is FilterBatchResult.Err)
}

@Test
fun uses_injected_runSession() = runBlocking {
    val stop = ByteArray(32)
    val result = openFilterSession(
        "1.2.3.4",
        8333,
        FilterSyncOptions(
            connect = { _, _ -> stubDuplex() },
            runSession = { _, _ ->
                object : FilterSessionApi {
                    override val services = 64uL
                    override suspend fun getCFCheckpt(stopHash: ByteArray) = listOf(ByteArray(32))
                    override suspend fun getCFHeaders(startHeight: Int, stopHash: ByteArray) =
                        CFHeadersResult(0, stop, ByteArray(32), listOf(ByteArray(32)))
                    override suspend fun getCFilters(
                        startHeight: Int,
                        stopHash: ByteArray,
                        expectCount: Int,
                        onFilter: (suspend (CFilterItem) -> Unit)?,
                    ) = listOf(CFilterItem(stop, byteArrayOf(1)))
                    override suspend fun close() {}
                }
            },
        ),
    )
    val ok = result as FilterBatchResult.Ok
    assertEquals(64uL, ok.value.services)
    assertEquals(1, ok.value.getCFCheckpt(stop).size)
}

@Test
fun refreshes_the_cfilter_timeout_whenever_activity_arrives() = runBlocking {
    val timeout = createInactivityTimeout(100, "cfilters")
    delay(60)
    timeout.refresh()
    delay(60)
    assertFalse(timeout.expired)
    delay(60)
    assertTrue(timeout.expired)
    assertEquals("cfilters inactive for 100ms", timeout.error?.message)
    timeout.clear()
}

@Test
fun allows_a_request_to_outlive_its_timeout_while_activity_continues() = runBlocking {
    val result = runWithInactivityTimeout(100, "cfilters") { activity ->
        delay(60)
        activity()
        delay(60)
        activity()
        "complete"
    }
    assertEquals("complete", result)
}
```

`stubDuplex()` already exists in `:peers` tests.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.FilterSyncTest`
Expected: FAIL — unresolved `openFilterSession`

- [ ] **Step 3: Write minimal implementation**

Port `filter-sync.ts`. Timeouts: `withTimeout` for connect/handshake and cfcheckpt/cfheaders; refreshable inactivity for getcfilters. Connect abort closes the duplex. `services` is `ULong`. Production `getC*` uses bip157 encode/decode + bip324 opaque short IDs.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.FilterSyncTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 3: `FilterSessionPool`

**Files:**
- Create: `peers/src/commonMain/kotlin/io/bluewallet/blueberry/peers/net/FilterSessionPool.kt`
- Create: `peers/src/commonTest/kotlin/io/bluewallet/blueberry/peers/net/FilterSessionPoolTest.kt`

**Interfaces:**
- Consumes: `FilterSessionApi`, `openFilterSession`, `TcpConnect`, `Config`
- Produces: `FilterPoolPeer`, `FilterSessionPoolOptions`, `FilterSessionPool`, `createFilterSessionPool`

- [ ] **Step 1: Write the failing tests**

Port every test in `filter-session-pool.test.ts`. `{ ok: true, value }` → `FilterBatchResult.Ok`; `{ ok: false, error }` → `Err`; `toBeNull()` → `assertNull`. `closeAll` during open: lease returns `null`, session `close()` ran, `fn` did not run.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.FilterSessionPoolTest`
Expected: FAIL — unresolved `createFilterSessionPool`

- [ ] **Step 3: Write minimal implementation**

Port `filter-session-pool.ts` 1:1. `generation++` on `closeAll`. Cool on `Err` or thrown lease. `onOpenCount` when busy or live session count changes.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :peers:jvmTest --tests io.bluewallet.blueberry.peers.net.FilterSessionPoolTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 4: `createFiltersDownloadModule`

**Files:**
- Create: `filters/src/commonMain/kotlin/io/bluewallet/blueberry/filters/modules/FiltersDownload.kt`
- Create: `filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/modules/FiltersDownloadTest.kt`
- Create: `filters/src/commonTest/kotlin/io/bluewallet/blueberry/filters/WaitFor.kt`

**Interfaces:**
- Consumes: `ModuleContext`, `FilterSessionPool` / injected `openSession`, `compactFilterFrom` / `inspectWalletBirthday`, storage filter repos
- Produces: `createFiltersDownloadModule(ctx, options): Module` name `filters-download`

- [ ] **Step 1: Write the failing tests**

Port every behaviour test in `filters-download.test.ts` except assertions that only check file-log `[filters-download]` text. Keep injected-`log` tests. Translation: `64n` → `NODE_COMPACT_FILTERS.toULong()`, `ok: true` → `FilterBatchResult.Ok`, `bus.on("filters:progress")` → `bus.on(Event.FiltersProgress)`, birthday sealed types, `waitFor` suspend, `runBlocking`. Copy helix3 fixtures: `buildFilterChain`, `buildFilterFixture` (998–1000), `buildGenesisFixture` (0–1000), `fakeRecord` / `mineHeader`.

`:filters` test deps need `implementation(libs.bip158)`, `implementation(libs.bitcoin.headers)`, `implementation(libs.sqldelight.sqlite.driver)` on androidHostTest (same as `:headers`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :filters:jvmTest --tests io.bluewallet.blueberry.filters.modules.FiltersDownloadTest`
Expected: FAIL — unresolved `createFiltersDownloadModule`

- [ ] **Step 3: Write minimal implementation**

Port `filters-download.ts` 1:1. `stop()` joins the loop without cancelling the in-flight run.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :filters:jvmTest --tests io.bluewallet.blueberry.filters.modules.FiltersDownloadTest`
Expected: PASS

- [ ] **Step 5: Commit**

Skip unless the user asks.

---

### Task 5: Progress store, hydrate, Filters DL UI, runtime

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/FiltersProgressStore.kt`
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersProgressStoreTest.kt`
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/FiltersHydrateTest.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersRuntime.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/PeersScreen.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt`
- Modify: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/PeersRuntimeTest.kt`

**Interfaces:**
- Consumes: `Event.FiltersProgress`, `createFiltersDownloadModule`
- Produces: store + hydrate + Start screen Filters DL; runtime starts all three modules

- [ ] **Step 1: Write the failing tests**

Port `filters-progress-store.test.ts` (percent 10, non-advancing keeps ETA null, 100/1000 then 200/1000 → ETA 8000, complete → 0; resume after complete resets samples → 1000/5000 then 1100/5000 → ETA 39000).

Hydrate: insert two `FilterRecord`s → downloaded 2, total 2; emit `total: 0` does not clobber; emit `total: 500` updates total, downloaded stays `min(stored, 500)`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.FiltersProgressStoreTest --tests io.bluewallet.blueberry.FiltersHydrateTest`
Expected: FAIL — unresolved references

- [ ] **Step 3: Write minimal implementation**

Port store/ETA. `hydrateFilters` uses `sessionOrDurableTotal` on `filters.count()`. Runtime: hydrate filters, bind `filters:progress`, start filters-download after headers. Screen adds Filters DL lines. `App` passes `filtersStore`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.FiltersProgressStoreTest --tests io.bluewallet.blueberry.FiltersHydrateTest --tests io.bluewallet.blueberry.PeersRuntimeTest :filters:jvmTest :peers:jvmTest`
Expected: PASS

- [ ] **Step 5: Full verification**

Run the spec Pass commands. Fix any failures.

- [ ] **Step 6: Commit**

Skip unless the user asks.
