# KMP onboarding flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the helix3 first-run onboarding flow into `:shared` Compose: create or import a wallet, persist the same KV keys, then show Click me in the same process.

**Architecture:** Boot routing and `sync_from_year` live in `io.bluewallet.blueberry.boot`. A pure step reducer lives in `onboarding`. Material3 screens call that reducer and `:wallet` persist helpers. `App` opens SQLite, runs the gate, and switches to the current Click me UI when both keys exist.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform Material3, `:storage` `createSqliteDatabase`, `:wallet` secret / mnemonic / birthday.

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-19-kmp-onboarding-flow-design.md`.
- Behaviour source: `/home/bigboss/Code/helix3/src/boot/onboarding-gate.ts`, `/home/bigboss/Code/helix3/src/sync-year.ts`, `/home/bigboss/Code/helix3/src/tui/OnboardingApp.tsx`, `/home/bigboss/Code/helix3/src/main.tsx` save path.
- Package boot: `io.bluewallet.blueberry.boot`. Package screens/flow: `io.bluewallet.blueberry.onboarding`. `App` stays `io.bluewallet.blueberry`.
- Material3 only. One UI for desktop, Android, and iOS. No TUI chrome (no ASCII BLUEBERRY, no magenta box, no ↑/↓/Enter/Esc hints).
- Buttons are `Continue` and `Back`. Titles and body strings match helix3 exactly.
- Year list is 2009–2026 inclusive (18 years). `DEFAULT_CHECKPOINT_YEAR = 2019`. `latestCheckpointYear() = 2026`. No header hex. No `CHECKPOINTS` map. No `consensusForYear`.
- Do not add a Gradle module. Do not change `:wallet` or `:storage` public APIs. Do not change Click me content.
- Do not re-exec the process. Do not add Compose UI tests. Do not add file logging. Do not open a helix3 `.sqlite` file.
- Do not log secrets or mnemonic words.
- Do not change Kotlin `2.4.10`.
- Pass `./gradlew :shared:jvmTest` and `./gradlew :shared:testAndroidHostTest`.
- iOS simulator tests stay optional on Linux.
- Do not commit unless the user asks.

## File structure

```
shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/SyncYear.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/OnboardingGate.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/DatabasePath.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersist.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/BootScreens.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt
shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt
shared/src/iosMain/kotlin/io/bluewallet/blueberry/MainViewController.kt
desktopApp/src/main/kotlin/io/bluewallet/blueberry/main.kt
androidApp/src/main/kotlin/io/bluewallet/blueberry/MainActivity.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/SyncYearTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/OnboardingGateTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/DatabasePathTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt
shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersistTest.kt
```

Spec: `docs/superpowers/specs/2026-08-19-kmp-onboarding-flow-design.md`.

---

### Task 1: SyncYear

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/SyncYear.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/SyncYearTest.kt`

**Interfaces:**
- Consumes: `io.bluewallet.blueberry.storage.Database` (`keyValue.get` / `keyValue.set`)
- Produces: `SYNC_FROM_YEAR_KEY`, `DEFAULT_CHECKPOINT_YEAR`, `SyncFromYearInspection`, `listCheckpointYears()`, `latestCheckpointYear()`, `parseSyncFromYear(raw: String?): Int?`, `inspectSyncFromYear(db: Database): SyncFromYearInspection`, `loadSyncFromYear(db: Database): Int`, `saveSyncFromYear(db: Database, year: Int)`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/SyncYearTest.kt`:

```kotlin
package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncYearTest {
    @Test
    fun listCheckpointYears_is_sorted_contiguous_2009_to_2026() {
        val years = listCheckpointYears()
        assertEquals(2009, years.first())
        assertEquals(2026, years.last())
        assertEquals(2026, latestCheckpointYear())
        assertEquals(18, years.size)
        assertEquals(2019, DEFAULT_CHECKPOINT_YEAR)
        for (i in 1 until years.size) {
            assertEquals(years[i - 1] + 1, years[i])
        }
    }

    @Test
    fun parseSyncFromYear_accepts_known_years_and_rejects_garbage() {
        assertEquals(2019, parseSyncFromYear("2019"))
        assertEquals(2015, parseSyncFromYear(" 2015 "))
        assertNull(parseSyncFromYear(null))
        assertNull(parseSyncFromYear(""))
        assertNull(parseSyncFromYear("1999"))
        assertNull(parseSyncFromYear("2019.0"))
        assertNull(parseSyncFromYear("019"))
        assertNull(parseSyncFromYear("abc"))
    }

    @Test
    fun save_load_round_trip_and_invalid_kv_reads_as_missing() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertFailsWith<IllegalArgumentException> { loadSyncFromYear(db) }.also {
            assertTrue(it.message!!.contains("sync_from_year"))
        }

        saveSyncFromYear(db, 2015)
        assertEquals("2015", db.keyValue.get(SYNC_FROM_YEAR_KEY))
        assertEquals(2015, loadSyncFromYear(db))
        assertEquals(SyncFromYearInspection.Ok(2015), inspectSyncFromYear(db))

        db.keyValue.set(SYNC_FROM_YEAR_KEY, "nope")
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertFailsWith<IllegalArgumentException> { saveSyncFromYear(db, 1999) }.also {
            assertTrue(it.message!!.contains("unknown"))
        }
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.SyncYearTest`

Expected: FAIL — `SyncYearKt` / `listCheckpointYears` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/SyncYear.kt`:

```kotlin
package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.storage.Database

const val SYNC_FROM_YEAR_KEY = "sync_from_year"
const val DEFAULT_CHECKPOINT_YEAR = 2019

sealed class SyncFromYearInspection {
    data object Missing : SyncFromYearInspection()
    data class Ok(val year: Int) : SyncFromYearInspection()
}

fun listCheckpointYears(): List<Int> = (2009..2026).toList()

fun latestCheckpointYear(): Int = listCheckpointYears().last()

fun parseSyncFromYear(raw: String?): Int? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val year = trimmed.toIntOrNull() ?: return null
    if (year.toString() != trimmed) return null
    if (year !in listCheckpointYears()) return null
    return year
}

fun inspectSyncFromYear(db: Database): SyncFromYearInspection {
    val year = parseSyncFromYear(db.keyValue.get(SYNC_FROM_YEAR_KEY))
    return if (year == null) SyncFromYearInspection.Missing else SyncFromYearInspection.Ok(year)
}

fun loadSyncFromYear(db: Database): Int {
    val inspected = inspectSyncFromYear(db)
    if (inspected !is SyncFromYearInspection.Ok) {
        throw IllegalArgumentException("sync_from_year missing or invalid")
    }
    return inspected.year
}

fun saveSyncFromYear(db: Database, year: Int) {
    if (year !in listCheckpointYears()) {
        throw IllegalArgumentException("unknown sync_from_year: $year")
    }
    db.keyValue.set(SYNC_FROM_YEAR_KEY, year.toString())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.SyncYearTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/SyncYear.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/SyncYearTest.kt
git commit -m "Add sync_from_year helpers with helix3 year list."
```

Skip this commit unless the user asked to commit.

---

### Task 2: OnboardingGate

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/OnboardingGate.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/OnboardingGateTest.kt`

**Interfaces:**
- Consumes: `WalletSecretInspection` from `:wallet`; `SyncFromYearInspection` from Task 1
- Produces: `OnboardingGate` (`ExitInvalid(detail: String)`, `Onboard(startAtYearStep: Boolean)`, `Start`) and `resolveOnboardingGate(wallet: WalletSecretInspection, year: SyncFromYearInspection): OnboardingGate`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/OnboardingGateTest.kt`:

```kotlin
package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.wallet.WalletSecretInspection
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingGateTest {
    @Test
    fun invalid_secret_always_exits() {
        assertEquals(
            OnboardingGate.ExitInvalid("bad"),
            resolveOnboardingGate(
                WalletSecretInspection.Invalid("bad"),
                SyncFromYearInspection.Ok(2019),
            ),
        )
    }

    @Test
    fun missing_secret_is_full_onboarding() {
        assertEquals(
            OnboardingGate.Onboard(startAtYearStep = false),
            resolveOnboardingGate(
                WalletSecretInspection.Missing,
                SyncFromYearInspection.Missing,
            ),
        )
    }

    @Test
    fun secret_ok_year_missing_is_year_step_only() {
        assertEquals(
            OnboardingGate.Onboard(startAtYearStep = true),
            resolveOnboardingGate(
                WalletSecretInspection.Ok("zpub…"),
                SyncFromYearInspection.Missing,
            ),
        )
    }

    @Test
    fun both_ok_starts_app() {
        assertEquals(
            OnboardingGate.Start,
            resolveOnboardingGate(
                WalletSecretInspection.Ok("zpub…"),
                SyncFromYearInspection.Ok(2015),
            ),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.OnboardingGateTest`

Expected: FAIL — `OnboardingGate` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/OnboardingGate.kt`:

```kotlin
package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.wallet.WalletSecretInspection

sealed class OnboardingGate {
    data class ExitInvalid(val detail: String) : OnboardingGate()
    data class Onboard(val startAtYearStep: Boolean) : OnboardingGate()
    data object Start : OnboardingGate()
}

fun resolveOnboardingGate(
    wallet: WalletSecretInspection,
    year: SyncFromYearInspection,
): OnboardingGate {
    if (wallet is WalletSecretInspection.Invalid) {
        return OnboardingGate.ExitInvalid(wallet.detail)
    }
    if (wallet is WalletSecretInspection.Missing) {
        return OnboardingGate.Onboard(startAtYearStep = false)
    }
    if (year is SyncFromYearInspection.Missing) {
        return OnboardingGate.Onboard(startAtYearStep = true)
    }
    return OnboardingGate.Start
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.OnboardingGateTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/OnboardingGate.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/OnboardingGateTest.kt
git commit -m "Add helix3 onboarding boot gate."
```

Skip this commit unless the user asked to commit.

---

### Task 3: OnboardingFlow

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt`

**Interfaces:**
- Consumes: `parseWalletSecret`, `generateMnemonic12` from `:wallet`; `DEFAULT_CHECKPOINT_YEAR`, `listCheckpointYears()` from Task 1
- Produces: `OnboardingStep`, `OnboardingState`, `OnboardingEvent`, `OnboardingEffect`, `OnboardingReduction`, `initialOnboardingState(startAtYearStep: Boolean): OnboardingState`, `reduceOnboarding(state: OnboardingState, event: OnboardingEvent): OnboardingReduction`

While `state.busy` is true, ignore every event except `PersistFailed` and `PersistImportOk`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.wallet.parseWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingFlowTest {
    private val abandon =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun choose_create_stores_twelve_words() {
        val start = initialOnboardingState(startAtYearStep = false)
        assertEquals(OnboardingStep.Choose, start.step)
        val result = reduceOnboarding(start, OnboardingEvent.ChooseCreate)
        assertEquals(OnboardingStep.Create, result.state.step)
        val words = result.state.mnemonic?.split(" ")
        assertNotNull(words)
        assertEquals(12, words.size)
        parseWalletSecret(result.state.mnemonic!!)
        assertNull(result.effect)
    }

    @Test
    fun choose_import_and_back() {
        val imported = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseImport,
        )
        assertEquals(OnboardingStep.Import, imported.state.step)
        assertEquals("", imported.state.importValue)

        val created = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseCreate,
        )
        val backFromCreate = reduceOnboarding(created.state, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Choose, backFromCreate.state.step)
        assertNull(backFromCreate.state.mnemonic)

        val typed = reduceOnboarding(
            imported.state,
            OnboardingEvent.ImportChanged("abc"),
        )
        val backFromImport = reduceOnboarding(typed.state, OnboardingEvent.Back)
        assertEquals(OnboardingStep.Choose, backFromImport.state.step)
        assertEquals("", backFromImport.state.importValue)
    }

    @Test
    fun submit_import_good_then_year_after_persist_ok() {
        var state = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseImport,
        ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertEquals(OnboardingStep.Import, submitted.state.step)
        assertTrue(submitted.state.busy)
        assertEquals(OnboardingEffect.PersistImportedSecret(abandon), submitted.effect)

        val year = reduceOnboarding(submitted.state, OnboardingEvent.PersistImportOk)
        assertEquals(OnboardingStep.Year, year.state.step)
        assertFalse(year.state.busy)
        assertNull(year.state.error)
        assertEquals(DEFAULT_CHECKPOINT_YEAR, year.state.selectedYear)
        assertNull(year.effect)
    }

    @Test
    fun submit_import_bad_stays_with_parse_error() {
        var state = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseImport,
        ).state
        state = reduceOnboarding(
            state,
            OnboardingEvent.ImportChanged("not a real mnemonic phrase at all"),
        ).state
        val submitted = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertEquals(OnboardingStep.Import, submitted.state.step)
        assertEquals("invalid BIP39 mnemonic", submitted.state.error)
        assertFalse(submitted.state.busy)
        assertNull(submitted.effect)
    }

    @Test
    fun confirm_create_emits_persist_created_not_year() {
        val created = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseCreate,
        )
        val confirmed = reduceOnboarding(created.state, OnboardingEvent.ConfirmCreate)
        assertEquals(OnboardingStep.Create, confirmed.state.step)
        assertTrue(confirmed.state.busy)
        val effect = confirmed.effect as OnboardingEffect.PersistCreatedWallet
        assertEquals(created.state.mnemonic, effect.mnemonic)
    }

    @Test
    fun confirm_year_emits_selected_year() {
        var state = initialOnboardingState(startAtYearStep = true)
        assertEquals(OnboardingStep.Year, state.step)
        assertEquals(DEFAULT_CHECKPOINT_YEAR, state.selectedYear)
        state = reduceOnboarding(state, OnboardingEvent.SelectYear(2015)).state
        val confirmed = reduceOnboarding(state, OnboardingEvent.ConfirmYear)
        assertTrue(confirmed.state.busy)
        assertEquals(OnboardingEffect.PersistYear(2015), confirmed.effect)
    }

    @Test
    fun back_on_year_does_nothing() {
        val start = initialOnboardingState(startAtYearStep = true)
        val back = reduceOnboarding(start, OnboardingEvent.Back)
        assertEquals(start, back.state)
        assertNull(back.effect)
    }

    @Test
    fun confirm_while_busy_is_ignored() {
        var state = reduceOnboarding(
            initialOnboardingState(false),
            OnboardingEvent.ChooseImport,
        ).state
        state = reduceOnboarding(state, OnboardingEvent.ImportChanged(abandon)).state
        val busy = reduceOnboarding(state, OnboardingEvent.SubmitImport)
        assertTrue(busy.state.busy)
        val ignored = reduceOnboarding(busy.state, OnboardingEvent.ConfirmYear)
        assertEquals(busy.state, ignored.state)
        assertNull(ignored.effect)
        val stillBusy = reduceOnboarding(busy.state, OnboardingEvent.SubmitImport)
        assertEquals(busy.state, stillBusy.state)
        assertNull(stillBusy.effect)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.onboarding.OnboardingFlowTest`

Expected: FAIL — `OnboardingFlowKt` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.DEFAULT_CHECKPOINT_YEAR
import io.bluewallet.blueberry.boot.listCheckpointYears
import io.bluewallet.blueberry.wallet.generateMnemonic12
import io.bluewallet.blueberry.wallet.parseWalletSecret

enum class OnboardingStep { Choose, Import, Create, Year }

data class OnboardingState(
    val step: OnboardingStep,
    val importValue: String = "",
    val error: String? = null,
    val mnemonic: String? = null,
    val selectedYear: Int = DEFAULT_CHECKPOINT_YEAR,
    val busy: Boolean = false,
)

sealed class OnboardingEvent {
    data object ChooseCreate : OnboardingEvent()
    data object ChooseImport : OnboardingEvent()
    data object Back : OnboardingEvent()
    data class ImportChanged(val value: String) : OnboardingEvent()
    data object SubmitImport : OnboardingEvent()
    data object ConfirmCreate : OnboardingEvent()
    data class SelectYear(val year: Int) : OnboardingEvent()
    data object ConfirmYear : OnboardingEvent()
    data class PersistFailed(val message: String) : OnboardingEvent()
    data object PersistImportOk : OnboardingEvent()
}

sealed class OnboardingEffect {
    data class PersistImportedSecret(val raw: String) : OnboardingEffect()
    data class PersistCreatedWallet(val mnemonic: String) : OnboardingEffect()
    data class PersistYear(val year: Int) : OnboardingEffect()
}

data class OnboardingReduction(
    val state: OnboardingState,
    val effect: OnboardingEffect? = null,
)

fun initialOnboardingState(startAtYearStep: Boolean): OnboardingState =
    if (startAtYearStep) OnboardingState(step = OnboardingStep.Year)
    else OnboardingState(step = OnboardingStep.Choose)

fun reduceOnboarding(state: OnboardingState, event: OnboardingEvent): OnboardingReduction {
    if (state.busy && event !is OnboardingEvent.PersistFailed && event !is OnboardingEvent.PersistImportOk) {
        return OnboardingReduction(state)
    }
    return when (event) {
        OnboardingEvent.ChooseCreate -> {
            if (state.step != OnboardingStep.Choose) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Create,
                    mnemonic = generateMnemonic12(),
                    error = null,
                    importValue = "",
                ),
            )
        }
        OnboardingEvent.ChooseImport -> {
            if (state.step != OnboardingStep.Choose) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Import,
                    importValue = "",
                    error = null,
                    mnemonic = null,
                ),
            )
        }
        OnboardingEvent.Back -> {
            if (state.step != OnboardingStep.Create && state.step != OnboardingStep.Import) {
                return OnboardingReduction(state)
            }
            OnboardingReduction(
                OnboardingState(step = OnboardingStep.Choose),
            )
        }
        is OnboardingEvent.ImportChanged -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            OnboardingReduction(state.copy(importValue = event.value, error = null))
        }
        OnboardingEvent.SubmitImport -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            try {
                parseWalletSecret(state.importValue)
            } catch (err: Exception) {
                return OnboardingReduction(
                    state.copy(error = err.message ?: err.toString(), busy = false),
                )
            }
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistImportedSecret(state.importValue),
            )
        }
        OnboardingEvent.ConfirmCreate -> {
            val mnemonic = state.mnemonic
            if (state.step != OnboardingStep.Create || mnemonic == null) {
                return OnboardingReduction(state)
            }
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistCreatedWallet(mnemonic),
            )
        }
        is OnboardingEvent.SelectYear -> {
            if (state.step != OnboardingStep.Year) return OnboardingReduction(state)
            if (event.year !in listCheckpointYears()) return OnboardingReduction(state)
            OnboardingReduction(state.copy(selectedYear = event.year))
        }
        OnboardingEvent.ConfirmYear -> {
            if (state.step != OnboardingStep.Year) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(busy = true, error = null),
                OnboardingEffect.PersistYear(state.selectedYear),
            )
        }
        is OnboardingEvent.PersistFailed -> {
            OnboardingReduction(state.copy(busy = false, error = event.message))
        }
        OnboardingEvent.PersistImportOk -> {
            if (state.step != OnboardingStep.Import) return OnboardingReduction(state)
            OnboardingReduction(
                state.copy(
                    step = OnboardingStep.Year,
                    busy = false,
                    error = null,
                    selectedYear = DEFAULT_CHECKPOINT_YEAR,
                ),
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.onboarding.OnboardingFlowTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlow.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingFlowTest.kt
git commit -m "Add onboarding step reducer with helix3 transitions."
```

Skip this commit unless the user asked to commit.

---

### Task 4: OnboardingPersist

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersist.kt`
- Test: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersistTest.kt`

**Interfaces:**
- Consumes: `saveWalletSecret`, `loadWalletSecret`, `inspectWalletBirthday`, `markWalletBirthdayPending` from `:wallet`; `saveSyncFromYear`, `loadSyncFromYear`, `inspectSyncFromYear`, `latestCheckpointYear()`, `SYNC_FROM_YEAR_KEY` from Task 1
- Produces: `persistImportedSecret(db: Database, raw: String)`, `persistCreatedWallet(db: Database, mnemonic: String)`, `persistSyncYear(db: Database, year: Int)`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersistTest.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.SYNC_FROM_YEAR_KEY
import io.bluewallet.blueberry.boot.SyncFromYearInspection
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.loadSyncFromYear
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.WalletBirthdayInspection
import io.bluewallet.blueberry.wallet.inspectWalletBirthday
import io.bluewallet.blueberry.wallet.loadWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingPersistTest {
    private val abandon =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun import_writes_secret_only() {
        val db = createSqliteDatabase(":memory:")
        persistImportedSecret(db, "  $abandon  ")
        assertEquals(abandon, loadWalletSecret(db))
        assertEquals(SyncFromYearInspection.Missing, inspectSyncFromYear(db))
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun create_writes_secret_latest_year_and_birthday_pending() {
        val db = createSqliteDatabase(":memory:")
        persistCreatedWallet(db, abandon)
        assertEquals(abandon, loadWalletSecret(db))
        assertEquals(2026, loadSyncFromYear(db))
        assertEquals(WalletBirthdayInspection.Pending, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun year_writes_sync_from_year() {
        val db = createSqliteDatabase(":memory:")
        persistSyncYear(db, 2015)
        assertEquals("2015", db.keyValue.get(SYNC_FROM_YEAR_KEY))
        assertEquals(2015, loadSyncFromYear(db))
        db.close()
    }

    @Test
    fun unknown_year_throws() {
        val db = createSqliteDatabase(":memory:")
        assertFailsWith<IllegalArgumentException> { persistSyncYear(db, 1999) }.also {
            assertTrue(it.message!!.contains("unknown"))
        }
        assertNull(db.keyValue.get(SYNC_FROM_YEAR_KEY))
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.onboarding.OnboardingPersistTest`

Expected: FAIL — `persistImportedSecret` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersist.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import io.bluewallet.blueberry.boot.latestCheckpointYear
import io.bluewallet.blueberry.boot.saveSyncFromYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.wallet.markWalletBirthdayPending
import io.bluewallet.blueberry.wallet.saveWalletSecret

fun persistImportedSecret(db: Database, raw: String) {
    saveWalletSecret(db, raw)
}

fun persistCreatedWallet(db: Database, mnemonic: String) {
    saveWalletSecret(db, mnemonic)
    saveSyncFromYear(db, latestCheckpointYear())
    markWalletBirthdayPending(db)
}

fun persistSyncYear(db: Database, year: Int) {
    saveSyncFromYear(db, year)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.onboarding.OnboardingPersistTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersist.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/onboarding/OnboardingPersistTest.kt
git commit -m "Persist onboarding secret, year, and birthday like helix3."
```

Skip this commit unless the user asked to commit.

---

### Task 5: Onboarding screens

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt`
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/BootScreens.kt`

**Interfaces:**
- Consumes: Task 3 reducer types; Task 1 `listCheckpointYears()`; persist callbacks with signatures `persistImportedSecret: (String) -> Unit`, `persistCreatedWallet: (String) -> Unit`, `persistSyncYear: (Int) -> Unit`
- Produces: `OnboardingApp(startAtYearStep: Boolean, onFinished: () -> Unit, persistImportedSecret: (String) -> Unit, persistCreatedWallet: (String) -> Unit, persistSyncYear: (Int) -> Unit)`, `InvalidSecretScreen(detail: String)`, `DatabaseOpenErrorScreen(message: String)`

No Compose UI tests. Compile plus existing unit tests are the check.

- [ ] **Step 1: Write BootScreens**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/BootScreens.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InvalidSecretScreen(detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(16.dp),
    ) {
        Text("wallet_secret is present but invalid: $detail")
        Text("Fix or delete the wallet_secret key in the database, then restart.")
    }
}

@Composable
fun DatabaseOpenErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(16.dp),
    ) {
        Text(message)
    }
}
```

- [ ] **Step 2: Write OnboardingApp**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt`:

```kotlin
package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bluewallet.blueberry.boot.listCheckpointYears

@Composable
fun OnboardingApp(
    startAtYearStep: Boolean,
    onFinished: () -> Unit,
    persistImportedSecret: (String) -> Unit,
    persistCreatedWallet: (String) -> Unit,
    persistSyncYear: (Int) -> Unit,
) {
    var state by remember(startAtYearStep) {
        mutableStateOf(initialOnboardingState(startAtYearStep))
    }

    fun dispatch(event: OnboardingEvent) {
        val reduction = reduceOnboarding(state, event)
        state = reduction.state
        when (val effect = reduction.effect) {
            is OnboardingEffect.PersistImportedSecret -> {
                try {
                    persistImportedSecret(effect.raw)
                    state = reduceOnboarding(state, OnboardingEvent.PersistImportOk).state
                } catch (err: Exception) {
                    state = reduceOnboarding(
                        state,
                        OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                    ).state
                }
            }
            is OnboardingEffect.PersistCreatedWallet -> {
                try {
                    persistCreatedWallet(effect.mnemonic)
                    onFinished()
                } catch (err: Exception) {
                    state = reduceOnboarding(
                        state,
                        OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                    ).state
                }
            }
            is OnboardingEffect.PersistYear -> {
                try {
                    persistSyncYear(effect.year)
                    onFinished()
                } catch (err: Exception) {
                    state = reduceOnboarding(
                        state,
                        OnboardingEvent.PersistFailed(err.message ?: err.toString()),
                    ).state
                }
            }
            null -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.step) {
            OnboardingStep.Choose -> {
                Text("Wallet")
                Text("Create a new wallet or import an existing one")
                Button(
                    onClick = { dispatch(OnboardingEvent.ChooseCreate) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create new wallet")
                }
                Button(
                    onClick = { dispatch(OnboardingEvent.ChooseImport) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import wallet")
                }
            }
            OnboardingStep.Import -> {
                Text("Import")
                Text("Enter BIP39 seed, account zpub, WIF private key, or address")
                OutlinedTextField(
                    value = state.importValue,
                    onValueChange = { dispatch(OnboardingEvent.ImportChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    singleLine = true,
                    placeholder = { Text("seed words, zpub, WIF, or address…") },
                )
                Text(state.error ?: "")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { dispatch(OnboardingEvent.Back) },
                        enabled = !state.busy,
                    ) { Text("Back") }
                    Button(
                        onClick = { dispatch(OnboardingEvent.SubmitImport) },
                        enabled = !state.busy,
                    ) { Text("Continue") }
                }
            }
            OnboardingStep.Create -> {
                Text("New seed")
                Text("Write down these 12 words. Anyone with them can spend your bitcoin.")
                val words = state.mnemonic?.split(" ").orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.chunked(3).forEachIndexed { row, rowWords ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            rowWords.forEachIndexed { col, word ->
                                val n = row * 3 + col + 1
                                Text(
                                    "$n. $word",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Text(if (state.busy) "Saving…" else state.error ?: "")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { dispatch(OnboardingEvent.Back) },
                        enabled = !state.busy,
                    ) { Text("Back") }
                    Button(
                        onClick = { dispatch(OnboardingEvent.ConfirmCreate) },
                        enabled = !state.busy,
                    ) { Text("Continue") }
                }
            }
            OnboardingStep.Year -> {
                Text("Sync from")
                Text("What year was the first transaction for this wallet?")
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(listCheckpointYears()) { year ->
                        val label = if (year == state.selectedYear) "• $year" else "$year"
                        Text(
                            label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.busy) {
                                    dispatch(OnboardingEvent.SelectYear(year))
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                Text(if (state.busy) "Saving…" else state.error ?: "")
                Button(
                    onClick = { dispatch(OnboardingEvent.ConfirmYear) },
                    enabled = !state.busy,
                ) { Text("Continue") }
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :shared:compileKotlinJvm`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run existing logic tests**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.SyncYearTest --tests io.bluewallet.blueberry.boot.OnboardingGateTest --tests io.bluewallet.blueberry.onboarding.OnboardingFlowTest --tests io.bluewallet.blueberry.onboarding.OnboardingPersistTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/OnboardingApp.kt \
  shared/src/commonMain/kotlin/io/bluewallet/blueberry/onboarding/BootScreens.kt
git commit -m "Add Material3 onboarding screens for create, import, and year."
```

Skip this commit unless the user asked to commit.

---

### Task 6: App gate, DB path, and platform entries

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/DatabasePath.kt`
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt`
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/DatabasePathTest.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt` (replace the whole file)
- Modify: `desktopApp/src/main/kotlin/io/bluewallet/blueberry/main.kt` (replace the whole file)
- Modify: `androidApp/src/main/kotlin/io/bluewallet/blueberry/MainActivity.kt` (replace the whole file)
- Modify: `shared/src/iosMain/kotlin/io/bluewallet/blueberry/MainViewController.kt` (replace the whole file)

**Interfaces:**
- Consumes: `blueberrySqlitePath(directory: String): String`; Task 1 inspect; Task 2 gate; Task 4 persist; Task 5 screens; `createSqliteDatabase`, `inspectWalletSecret`
- Produces: `App(databasePath: String)` that opens SQLite, routes, and shows Click me only when the gate is `Start`

- [ ] **Step 1: Write the failing path test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/DatabasePathTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.boot.DatabasePathTest`

Expected: FAIL — `blueberrySqlitePath` is unresolved.

- [ ] **Step 3: Implement path helper, Click me extract, App, and entries**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/DatabasePath.kt`:

```kotlin
package io.bluewallet.blueberry.boot

fun blueberrySqlitePath(directory: String): String {
    val trimmed = directory.trimEnd('/', '\\')
    return "$trimmed/blueberry.sqlite"
}
```

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt` with the current Click me body (do not change the button, animation, image, or vendor lines):

```kotlin
package io.bluewallet.blueberry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import blueberry.shared.generated.resources.Res
import blueberry.shared.generated.resources.compose_multiplatform
import org.jetbrains.compose.resources.painterResource

@Composable
fun ClickMeContent() {
    var showContent by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .safeContentPadding()
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = { showContent = !showContent }) {
            Text("Click me!")
        }
        AnimatedVisibility(showContent) {
            val lines = remember { vendorLibraryStatus() }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(Res.drawable.compose_multiplatform), null)
                lines.forEach { line ->
                    Text(line)
                }
            }
        }
    }
}
```

Replace `shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt` with:

```kotlin
package io.bluewallet.blueberry

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.OnboardingGate
import io.bluewallet.blueberry.boot.inspectSyncFromYear
import io.bluewallet.blueberry.boot.resolveOnboardingGate
import io.bluewallet.blueberry.onboarding.DatabaseOpenErrorScreen
import io.bluewallet.blueberry.onboarding.InvalidSecretScreen
import io.bluewallet.blueberry.onboarding.OnboardingApp
import io.bluewallet.blueberry.onboarding.persistCreatedWallet
import io.bluewallet.blueberry.onboarding.persistImportedSecret
import io.bluewallet.blueberry.onboarding.persistSyncYear
import io.bluewallet.blueberry.storage.Database
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.inspectWalletSecret

private class OpenedDatabase(path: String) {
    val result: Result<Database> = runCatching { createSqliteDatabase(path) }
    fun close() {
        result.getOrNull()?.close()
    }
}

@Composable
@Preview
fun App(databasePath: String = ":memory:") {
    MaterialTheme {
        val opened = remember(databasePath) { OpenedDatabase(databasePath) }
        DisposableEffect(opened) {
            onDispose { opened.close() }
        }
        val db = opened.result.getOrNull()
        val openError = opened.result.exceptionOrNull()
        if (openError != null) {
            DatabaseOpenErrorScreen(openError.message ?: openError.toString())
            return@MaterialTheme
        }
        checkNotNull(db)
        var gate by remember(databasePath) {
            mutableStateOf(
                resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db)),
            )
        }
        fun refreshGate() {
            gate = resolveOnboardingGate(inspectWalletSecret(db), inspectSyncFromYear(db))
        }
        when (val current = gate) {
            is OnboardingGate.Start -> ClickMeContent()
            is OnboardingGate.ExitInvalid -> InvalidSecretScreen(current.detail)
            is OnboardingGate.Onboard -> OnboardingApp(
                startAtYearStep = current.startAtYearStep,
                onFinished = { refreshGate() },
                persistImportedSecret = { persistImportedSecret(db, it) },
                persistCreatedWallet = { persistCreatedWallet(db, it) },
                persistSyncYear = { persistSyncYear(db, it) },
            )
        }
    }
}
```

Replace `desktopApp/src/main/kotlin/io/bluewallet/blueberry/main.kt` with:

```kotlin
package io.bluewallet.blueberry

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.bluewallet.blueberry.boot.blueberrySqlitePath
import java.io.File

fun main() = application {
    val dir = File("blueberry.data")
    dir.mkdirs()
    val path = blueberrySqlitePath(dir.absolutePath)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Blueberry",
    ) {
        App(databasePath = path)
    }
}
```

Replace `androidApp/src/main/kotlin/io/bluewallet/blueberry/MainActivity.kt` with:

```kotlin
package io.bluewallet.blueberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.bluewallet.blueberry.boot.blueberrySqlitePath

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val path = blueberrySqlitePath(filesDir.absolutePath)
        setContent {
            App(databasePath = path)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(databasePath = ":memory:")
}
```

Replace `shared/src/iosMain/kotlin/io/bluewallet/blueberry/MainViewController.kt` with:

```kotlin
package io.bluewallet.blueberry

import androidx.compose.ui.window.ComposeUIViewController
import io.bluewallet.blueberry.boot.blueberrySqlitePath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun MainViewController() = ComposeUIViewController {
    App(databasePath = iosDatabasePath())
}

private fun iosDatabasePath(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val directory = requireNotNull(url?.path) { "Application Support directory is missing" }
    return blueberrySqlitePath(directory)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :shared:jvmTest
./gradlew :shared:testAndroidHostTest
```

Expected: BUILD SUCCESSFUL. All previous `:shared` tests still pass. New tests pass: `SyncYearTest`, `OnboardingGateTest`, `OnboardingFlowTest`, `OnboardingPersistTest`, `DatabasePathTest`.

- [ ] **Step 5: Compile desktop entry**

Run: `./gradlew :desktopApp:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Manual desktop check**

Run: `./gradlew :desktopApp:run`

Expected:

1. Empty `blueberry.data` (or delete `blueberry.data/blueberry.sqlite` first) shows **Wallet** with Create / Import, not Click me.
2. Create → Continue → Click me. Quit and run again → Click me (no onboarding).
3. Delete the sqlite file. Import a valid 12-word seed → year list. Quit before Continue. Run again → year list only.

Do not print the seed in logs.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/io/bluewallet/blueberry/boot/DatabasePath.kt \
  shared/src/commonMain/kotlin/io/bluewallet/blueberry/ClickMeContent.kt \
  shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/boot/DatabasePathTest.kt \
  shared/src/iosMain/kotlin/io/bluewallet/blueberry/MainViewController.kt \
  desktopApp/src/main/kotlin/io/bluewallet/blueberry/main.kt \
  androidApp/src/main/kotlin/io/bluewallet/blueberry/MainActivity.kt
git commit -m "Gate App start behind helix3 onboarding and persist paths."
```

Skip this commit unless the user asked to commit.

---

## Self-review

**Spec coverage**

| Spec item | Task |
| --- | --- |
| `SyncYear` API, 2009–2026, default 2019, latest 2026 | 1 |
| `OnboardingGate` table | 2 |
| Flow events/effects, create skips year, import → year, back rules, busy ignore | 3 |
| Persist import / create / year | 4 |
| Material3 screens and helix3 copy | 5 |
| Invalid secret + DB open error screens | 5 |
| `App` open DB, gate, in-process Click me | 6 |
| Desktop / Android / iOS paths | 6 |
| `blueberrySqlitePath` | 6 |
| Gate + year + flow tests | 1–3 |
| Persist rules | 4 |
| No Compose UI tests, no consensus, Click me unchanged | 5–6 |

**Placeholder scan:** no TBD / later / “add tests for the above”.

**Type consistency:** `SyncFromYearInspection`, `OnboardingGate`, `OnboardingEvent`, `OnboardingEffect`, persist function names, and `App(databasePath: String)` match the spec and later tasks.
