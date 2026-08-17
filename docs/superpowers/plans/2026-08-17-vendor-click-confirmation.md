# Vendor click confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show four live vendor-library status lines when the user taps **Click me!**.

**Architecture:** A pure function `vendorLibraryStatus()` in `:shared` commonMain calls the four vendor APIs and returns four strings. `App()` draws those strings. Tests cover the strings and the per-line error wrapper. No new screens.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform, `io.bluewallet.headers`, `io.bluewallet.bip324`, `io.bluewallet.bip157`, `io.bluewallet.bip158`.

## Global Constraints

- Button label stays `Click me!`.
- Keep the Compose Multiplatform logo.
- Do not show `Compose: $greeting`.
- Exact success lines, in this order: `headers: checkpoint 665280`; `bip324: mainnet port 8333`; `bip157: NODE_COMPACT_FILTERS 64`; `bip158: hex 00 size 1`.
- Values come from `MAINNET_HEADER_CONSENSUS.checkpoint.height`, `Networks.mainnet.defaultPort`, `NODE_COMPACT_FILTERS`, `hexToBytes("00").size`.
- If one call throws, that line is `name: error ` plus the exception message. The other lines still show.
- Function name is `vendorLibraryStatus(): List<String>` in `:shared` commonMain. No UI inside that function.
- Do not mock the four libraries in the success test.
- Do not add screens, navigation, theming, network, sync, or wallet work.
- Do not change vendor library APIs.
- Pass `./gradlew :shared:jvmTest` and `./gradlew :shared:testAndroidHostTest`.
- Do not commit unless the user asks.

---

### Task 1: vendorLibraryStatus success lines

**Files:**
- Create: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/VendorLibraryStatus.kt`
- Modify: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt`

**Interfaces:**
- Consumes: `MAINNET_HEADER_CONSENSUS`, `Networks.mainnet.defaultPort`, `NODE_COMPACT_FILTERS`, `hexToBytes`
- Produces: `fun vendorLibraryStatus(): List<String>`

- [ ] **Step 1: Write the failing success test**

Replace `VendorLibrariesTest` with:

```kotlin
package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class VendorLibrariesTest {

    @Test
    fun vendorLibraryStatus_returns_four_success_lines() {
        assertEquals(
            listOf(
                "headers: checkpoint 665280",
                "bip324: mainnet port 8333",
                "bip157: NODE_COMPACT_FILTERS 64",
                "bip158: hex 00 size 1",
            ),
            vendorLibraryStatus(),
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.VendorLibrariesTest`

Expected: FAIL because `vendorLibraryStatus` is not defined.

- [ ] **Step 3: Write the success implementation**

Create `shared/src/commonMain/kotlin/io/bluewallet/blueberry/VendorLibraryStatus.kt`:

```kotlin
package io.bluewallet.blueberry

import io.bluewallet.bip157.NODE_COMPACT_FILTERS
import io.bluewallet.bip158.hexToBytes
import io.bluewallet.bip324.Networks
import io.bluewallet.headers.MAINNET_HEADER_CONSENSUS

fun vendorLibraryStatus(): List<String> = listOf(
    vendorStatusLine("headers") {
        "headers: checkpoint ${MAINNET_HEADER_CONSENSUS.checkpoint.height}"
    },
    vendorStatusLine("bip324") {
        "bip324: mainnet port ${Networks.mainnet.defaultPort}"
    },
    vendorStatusLine("bip157") {
        "bip157: NODE_COMPACT_FILTERS $NODE_COMPACT_FILTERS"
    },
    vendorStatusLine("bip158") {
        "bip158: hex 00 size ${hexToBytes("00").size}"
    },
)

internal fun vendorStatusLine(name: String, block: () -> String): String =
    try {
        block()
    } catch (error: Exception) {
        "$name: error ${error.message}"
    }
```

- [ ] **Step 4: Run the success test**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.VendorLibrariesTest`

Expected: PASS

- [ ] **Step 5: Commit only if the user asked**

Do not commit in this task unless the user asked for a commit.

---

### Task 2: vendorStatusLine error text

**Files:**
- Modify: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt`
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/VendorLibraryStatus.kt` (only if the error test fails)

**Interfaces:**
- Consumes: `internal fun vendorStatusLine(name: String, block: () -> String): String`
- Produces: error line format `$name: error ${error.message}`

- [ ] **Step 1: Write the failing error test**

Add this test to `VendorLibrariesTest`:

```kotlin
    @Test
    fun vendorStatusLine_uses_error_prefix_when_block_throws() {
        assertEquals(
            "headers: error boom",
            vendorStatusLine("headers") { throw Exception("boom") },
        )
    }
```

- [ ] **Step 2: Run the error test**

Run: `./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.VendorLibrariesTest`

Expected: PASS if Task 1 already added `vendorStatusLine`. If it fails, implement the `try/catch` from Task 1 Step 3 and re-run until PASS.

- [ ] **Step 3: Run both shared JVM and Android host tests**

Run: `./gradlew :shared:jvmTest :shared:testAndroidHostTest`

Expected: BUILD SUCCESSFUL. Both `VendorLibrariesTest` methods pass.

- [ ] **Step 4: Commit only if the user asked**

Do not commit in this task unless the user asked for a commit.

---

### Task 3: Show the four lines in App

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/bluewallet/blueberry/App.kt`

**Interfaces:**
- Consumes: `fun vendorLibraryStatus(): List<String>`
- Produces: `App()` panel text is the four status lines

- [ ] **Step 1: Replace the greeting text with status lines**

In `App()`, keep the button and the logo. Remove `Greeting().greet()` and `Text("Compose: $greeting")`. Draw each status line as `Text`.

The `AnimatedVisibility` block must be:

```kotlin
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
```

Leave `Greeting.kt` in place. Do not change the button label.

- [ ] **Step 2: Compile the Android app**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Re-run shared tests**

Run: `./gradlew :shared:jvmTest :shared:testAndroidHostTest`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual check in Android Studio**

Sync Gradle. Run the Android app. Tap **Click me!**. Confirm the logo and these four lines:

```
headers: checkpoint 665280
bip324: mainnet port 8333
bip157: NODE_COMPACT_FILTERS 64
bip158: hex 00 size 1
```

- [ ] **Step 5: Commit only if the user asked**

Do not commit in this task unless the user asked for a commit.
