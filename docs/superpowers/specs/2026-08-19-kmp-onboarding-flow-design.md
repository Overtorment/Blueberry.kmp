# KMP onboarding flow

Date: 2026-08-19  
Status: approved (conversation)

## Goal

Port the helix3 first-run onboarding flow into Blueberry.kmp.

The user creates or imports a wallet, then (on import) picks a sync year. The app persists the same KV keys as helix3. After a successful finish, the current Click me screen opens in the same process.

Screens use standard Material3 controls. The same Compose UI runs on desktop, Android, and iOS.

## Non-goals

- helix3 TUI chrome (ASCII BLUEBERRY, magenta box, ↑/↓/Enter/Esc hints)
- A new Gradle module
- Header hex, `CHECKPOINTS` records, or `consensusForYear`
- Message bus, sync modules, or the main helix3 dashboard
- Process re-exec after save
- In-app change or reset of `wallet_secret` or `sync_from_year` after save
- File logging
- Compose UI tests
- Open a helix3 `.sqlite` file
- Change `:wallet` or `:storage` public APIs
- Change Click me content

## Decisions

| Topic | Choice |
| --- | --- |
| UI | Material3 in `:shared`. One UI for all app targets. |
| Module split | Gate, year helpers, flow, and screens in `:shared`. `:wallet` stays secret / mnemonic / birthday only. |
| Year data | Year numbers 2009–2026 only. Persist `sync_from_year`. No header consensus. |
| After save | Re-run the gate in process. Show Click me. |
| Invalid secret in KV | Blocking error screen. No edit. No Click me. |
| DB path | `App` takes `databasePath`. Each app entry point passes the platform path. |

## Stack

- Module: `:shared` (existing Compose Multiplatform)
- Package for boot: `io.bluewallet.blueberry.boot`
- Package for screens and flow: `io.bluewallet.blueberry.onboarding`
- `App` stays in `io.bluewallet.blueberry`
- Reuse `:wallet` (`parseWalletSecret`, `saveWalletSecret`, `inspectWalletSecret`, `generateMnemonic12`, `markWalletBirthdayPending`)
- Reuse `:storage` (`createSqliteDatabase`, `keyValue`)
- Do not change Kotlin `2.4.10`

## helix3 map

| helix3 | Kotlin |
| --- | --- |
| `src/boot/onboarding-gate.ts` | `boot/OnboardingGate.kt` |
| `src/sync-year.ts` | `boot/SyncYear.kt` |
| `DEFAULT_CHECKPOINT_YEAR` in `checkpoint.ts` | `DEFAULT_CHECKPOINT_YEAR` in `SyncYear.kt` |
| `src/tui/OnboardingApp.tsx` step state | `onboarding/OnboardingFlow.kt` |
| `src/tui/OnboardingApp.tsx` view | `onboarding/OnboardingApp.kt` |
| `src/main.tsx` save + boot | `onboarding/OnboardingPersist.kt` + `App.kt` |

Do not port `checkpoint.ts` header hex or `src/boot/reexec.ts`.

## Units

| Unit | Job | Depends on |
| --- | --- | --- |
| `SyncYear` | Year list, parse, inspect, load, save `sync_from_year` | `:storage` KV |
| `OnboardingGate` | Same routing as helix3 `resolveOnboardingGate` | `WalletSecretInspection`, year inspect |
| `OnboardingFlow` | Step machine only. No KV writes. | `:wallet` `parseWalletSecret`, `generateMnemonic12` |
| `OnboardingPersist` | Write secret / year / birthday on success | `:wallet`, `SyncYear` |
| `OnboardingApp` | Material3 screens | Flow + persist callbacks |
| `App` | Open DB, run gate, show onboarding, error, or Click me | Gate, persist, current Click me |
| `blueberrySqlitePath` | Join a directory with `blueberry.sqlite` | none |

## Public API

### `SyncYear`

Names match helix3 `sync-year.ts`.

```kotlin
const val SYNC_FROM_YEAR_KEY = "sync_from_year"
const val DEFAULT_CHECKPOINT_YEAR = 2019

fun listCheckpointYears(): List<Int>   // 2009..2026 inclusive, ascending
fun latestCheckpointYear(): Int        // 2026
fun parseSyncFromYear(raw: String?): Int?
fun inspectSyncFromYear(db: Database): SyncFromYearInspection
fun loadSyncFromYear(db: Database): Int
fun saveSyncFromYear(db: Database, year: Int)
```

`SyncFromYearInspection` is `Missing` or `Ok(year: Int)`. Invalid or unknown KV text is `Missing` (same as helix3).

`parseSyncFromYear` accepts a trimmed canonical decimal year that is in `listCheckpointYears()`. Reject null, empty, `1999`, `2019.0`, `019`, and `abc`.

`saveSyncFromYear` writes `String(year)` to `SYNC_FROM_YEAR_KEY`. Unknown year throws `unknown sync_from_year: {year}`.

`loadSyncFromYear` throws `sync_from_year missing or invalid` when inspect is not `Ok`.

There is no `CHECKPOINTS` map. The known set is the closed range 2009–2026 (18 years, contiguous). That matches today’s helix3 keys.

### `OnboardingGate`

```kotlin
sealed class OnboardingGate {
    data class ExitInvalid(val detail: String) : OnboardingGate()
    data class Onboard(val startAtYearStep: Boolean) : OnboardingGate()
    data object Start : OnboardingGate()
}

fun resolveOnboardingGate(
    wallet: WalletSecretInspection,
    year: SyncFromYearInspection,
): OnboardingGate
```

| `wallet_secret` | `sync_from_year` | Result |
| --- | --- | --- |
| `Invalid` | any | `ExitInvalid(detail)` |
| `Missing` | any | `Onboard(startAtYearStep = false)` |
| `Ok` | `Missing` | `Onboard(startAtYearStep = true)` |
| `Ok` | `Ok` | `Start` |

### `OnboardingFlow`

Steps: `Choose`, `Import`, `Create`, `Year`.

`initialOnboardingState(startAtYearStep)` is `Year` when `startAtYearStep` is true, else `Choose`.

`reduceOnboarding(state, event)` returns the next state plus an optional persist effect. It never writes KV.

Events: `ChooseCreate`, `ChooseImport`, `Back`, `ImportChanged(value)`, `SubmitImport`, `ConfirmCreate`, `SelectYear(year)`, `ConfirmYear`, `PersistFailed(message)`, `PersistImportOk`.

Effects: `PersistImportedSecret(raw)`, `PersistCreatedWallet(mnemonic)`, `PersistYear(year)`.

| Event | Result |
| --- | --- |
| Choose → Create | `Create`. Call `generateMnemonic12()`. Store the 12 words. Clear error. |
| Choose → Import | `Import`. Empty field. Clear error. |
| Back on Create or Import | `Choose`. Drop mnemonic and import text. |
| Back on Choose or Year | No change. Year has no back (same as helix3). |
| Import text change | Update field. Clear error. |
| Submit Import | `parseWalletSecret`. Fail: stay on Import, set error to the exception message. Success: stay on Import, `busy = true`, effect `PersistImportedSecret(raw)`. |
| Confirm Create | If mnemonic is set and not busy: `busy = true`, effect `PersistCreatedWallet(mnemonic)`. |
| Year select | Store `selectedYear` (must be in `listCheckpointYears()`). Initial value is `DEFAULT_CHECKPOINT_YEAR` (2019). |
| Confirm Year | If not busy: `busy = true`, effect `PersistYear(selectedYear)`. |
| Persist failed | `busy = false`. Stay on the same step. Show the error. Import failures use the field error. Create and year failures use a one-line error under the actions. |
| Persist import ok | `Year`, `busy = false`, clear error. `selectedYear` stays 2019. |
| Persist create or year ok | Not a flow step. `App` re-runs the gate and shows Click me. |

Ignore submit/confirm while `busy` is true.

### `OnboardingPersist`

```kotlin
fun persistImportedSecret(db: Database, raw: String)
fun persistCreatedWallet(db: Database, mnemonic: String)
fun persistSyncYear(db: Database, year: Int)
```

- Import: `saveWalletSecret(db, raw)` only.
- Create: `saveWalletSecret(db, mnemonic)`, `saveSyncFromYear(db, latestCheckpointYear())`, `markWalletBirthdayPending(db)`.
- Year: `saveSyncFromYear(db, year)` only.

## Screens

Material3. Existing `MaterialTheme` in `App`. No new color theme.

Use the helix3 titles and body strings. Do not use TUI key hints. Buttons are **Continue** and **Back**.

| Step | Title | Body | Controls |
| --- | --- | --- | --- |
| Choose | `Wallet` | `Create a new wallet or import an existing one` | Button `Create new wallet`. Button `Import wallet`. |
| Import | `Import` | `Enter BIP39 seed, account zpub, WIF private key, or address` | `OutlinedTextField`, placeholder `seed words, zpub, WIF, or address…`. Echo text. No mask. Continue. Back. Error or empty hint under the field. |
| Create | `New seed` | `Write down these 12 words. Anyone with them can spend your bitcoin.` | 4×3 numbered word grid (`1. word` … `12. word`) in mnemonic order. Continue. Back. While busy show `Saving…` and disable actions. |
| Year | `Sync from` | `What year was the first transaction for this wallet?` | Scrollable year list `2009` … `2026`. Tap selects. Continue confirms. Start with 2019 selected. No Back. While busy show `Saving…`. |

Invalid-secret screen (not a flow step):

1. `wallet_secret is present but invalid: {detail}`
2. `Fix or delete the wallet_secret key in the database, then restart.`

DB open failure: show the exception message on a blocking screen. No onboarding. No Click me.

Click me stays the current vendor-status button UI. `App` shows it only when the gate is `Start`.

## Data flow

Cold start:

1. Platform entry passes `databasePath` into `App`.
2. `App` opens `createSqliteDatabase(databasePath)`. Close the DB when `App` leaves composition.
3. Inspect `wallet_secret` and `sync_from_year`.
4. `resolveOnboardingGate` picks onboarding, Click me, or the invalid-secret screen.

Create: generate words → user confirms → persist secret + year 2026 + birthday pending → re-run gate → Click me.

Import: validate and persist secret → year list → persist year → re-run gate → Click me.

Resume: secret present and year missing → year step only. Both keys ok → Click me.

Quit after a saved secret and before year: next launch opens the year step.

Do not log secrets or mnemonic words.

## Database path

Common helper: `blueberrySqlitePath(directory)` → `{directory}/blueberry.sqlite`.

| Entry | Directory |
| --- | --- |
| `desktopApp` `main.kt` | `blueberry.data` under the process working directory. Create the directory if it is missing. |
| `androidApp` `MainActivity` | `filesDir` |
| iOS `MainViewController` | `NSFileManager` Application Support directory for the app (`NSApplicationSupportDirectory` in `NSUserDomainMask`). Create the directory if it is missing. |

Tests use `createSqliteDatabase(":memory:")`. They do not open the app file.

## Error messages

| Case | Message or behavior |
| --- | --- |
| Empty import | `wallet secret is empty` (`parseWalletSecret`) |
| Bad mnemonic / zpub / WIF / address | Existing `:wallet` messages |
| Unknown year save | `unknown sync_from_year: {year}` |
| Missing year load | `sync_from_year missing or invalid` |
| Bad import on screen | Inline field error. No KV write. Stay on Import. |
| Persist failure | Stay on the current step. Show the error. Do not show Click me. |
| Invalid KV secret | Blocking screen. Leave the KV row as-is. |
| DB open failure | Blocking screen with the exception message. |

## Testing

`commonTest` in `:shared`. No Compose UI tests.

Port:

- helix3 `tests/unit/onboarding-gate.test.ts` → `OnboardingGateTest`
- helix3 `tests/unit/sync-year.test.ts` → `SyncYearTest` (assert 18 years 2009–2026 and latest 2026; there is no `CHECKPOINTS` object)

Add `OnboardingFlowTest`:

- Choose → Create stores 12 words and goes to Create
- Choose → Import
- Back from Create or Import → Choose
- Submit good import → persist-import effect, then Year after persist-ok
- Submit bad import → stay Import with the parse error
- Confirm Create → persist-created effect (no Year step)
- Confirm Year → persist-year effect with the selected year
- Back on Year does nothing
- Confirm while busy is ignored

Keep existing `:shared` vendor and greeting tests.

Pass:

- `./gradlew :shared:jvmTest`
- `./gradlew :shared:testAndroidHostTest`

iOS simulator tests stay optional on Linux.

Manual check after implement: desktop run. Empty DB shows Choose. Create then relaunch shows Click me. Import then quit before year; relaunch shows the year list.

## Success

- First launch with an empty DB shows onboarding, not Click me
- Create and import match helix3 persist rules and copy
- Import asks for a year; create does not
- Relaunch with both keys shows Click me
- Gate, year, and flow unit tests pass on JVM and Android host
- No header consensus code
- Click me content is unchanged
