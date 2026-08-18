# KMP wallet module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `:wallet` that ports helix3 `src/wallet` with the same public names, errors, and tests.

**Architecture:** A KMP module wraps Maven `bitcoin-kmp` and talks to `:storage` KV. Callers see helix3-shaped types only (`ByteArray`, `Long`, `SendAmount`). UTXO-to-tx logic stays inside `BuildSendTx.kt`. `log()` is a no-op.

**Tech Stack:** Kotlin 2.4.10, `fr.acinq.bitcoin:bitcoin-kmp:0.31.0`, secp256k1-kmp JNI `0.23.0`, `:storage`.

## Global Constraints

- Public names match helix3 `src/wallet` exports. Behaviour source is `/home/bigboss/Code/helix3/src/wallet/*.ts`.
- Package is `io.bluewallet.blueberry.wallet`.
- ACINQ types stay inside `:wallet`. They are not part of the public API.
- `Uint8Array` → `ByteArray`. Sats/fee `bigint` → `Long`. Index/vsize/height → `Int`. `feeRateSatPerVb` → `Double`. `amountSats` → `SendAmount.Exact` or `SendAmount.Max`.
- Error strings match the spec table exactly.
- `initialWatchCount = 100`, `gapLimit = 100`, watch cap `10_000`.
- `log(scope, message)` is a no-op. Do not write files. Do not assert log text.
- Do not port TUI, parse-blocks, filters-matching, message bus, or file log.
- Skip the receive-store test and the parse-blocks gap test in `address-watch-wallet.test.ts`.
- No public select-UTXO helper. `buildSend` uses every UTXO the caller passed.
- No new vendor git submodule. No extra Maven UR library.
- Do not change Kotlin `2.4.10`. Do not change Click me or Compose UI.
- Pass `./gradlew :wallet:jvmTest` and `./gradlew :wallet:testAndroidHostTest`.
- Do not commit unless the user asks.

## File structure

```
settings.gradle.kts
gradle/libs.versions.toml
shared/build.gradle.kts
wallet/build.gradle.kts
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Log.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Hex.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.kt
wallet/src/androidMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.android.kt
wallet/src/iosMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.ios.kt
wallet/src/jvmMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.jvm.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/IsAddressValid.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Secret.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Derive.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Wallet.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Birthday.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddress.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/GenerateMnemonic.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt
wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt
wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/TestVectors.kt
wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/*.kt
```

Behaviour source: `/home/bigboss/Code/helix3/src/wallet/`. Spec: `docs/superpowers/specs/2026-08-18-kmp-wallet-module-design.md`.

bitcoin-kmp notes the implementer must use:

- `MnemonicCode.validate`, `MnemonicCode.toSeed(words, "")`, `MnemonicCode.toMnemonics(entropy)`
- `DeterministicWallet.generate(seed)`, `derivePrivateKey("m/84'/0'/0'")`, `ExtendedPublicKey.decode` / `encode`
- `DeterministicWallet.zpub` (`0x04b24746`) and `zprv` (`0x04b2430c`) — same as helix3
- `Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, address)` and `Bitcoin.computeP2WpkhAddress` / `computeP2PkhAddress` / `computeP2ShOfP2WpkhAddress` / `computeBIP86Address`
- `Script.write` / `Script.parse` / `Script.pay2wpkh` / `Script.pay2pkh` / `Script.pay2sh` / taproot script `OP_1` + 32-byte x-only
- `fr.acinq.bitcoin.psbt.Psbt` for unsigned PSBT hex
- JNI: `secp256k1-kmp-jni-jvm` on JVM, `secp256k1-kmp-jni-android` on Android

---

### Task 1: `:wallet` module and bitcoin-kmp smoke

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `wallet/build.gradle.kts`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BitcoinKmpSmokeTest.kt`

**Interfaces:**
- Consumes: none
- Produces: Gradle project `:wallet` that compiles against bitcoin-kmp

- [ ] **Step 1: Write the failing smoke test**

Create `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BitcoinKmpSmokeTest.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals

class BitcoinKmpSmokeTest {
    @Test
    fun abandon_seed_is_64_bytes_and_zpub_prefix_matches_slip0132() {
        val seed = MnemonicCode.toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "",
        )
        assertEquals(64, seed.size)
        assertEquals(0x04b24746, DeterministicWallet.zpub)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BitcoinKmpSmokeTest`

Expected: FAIL — project `:wallet` does not exist.

- [ ] **Step 3: Wire the module**

In `settings.gradle.kts` add `include(":wallet")` next to `include(":storage")`.

In `gradle/libs.versions.toml` add:

```toml
bitcoin-kmp = "0.31.0"
secp256k1-kmp = "0.23.0"
```

and under `[libraries]`:

```toml
bitcoin-kmp = { module = "fr.acinq.bitcoin:bitcoin-kmp", version.ref = "bitcoin-kmp" }
secp256k1-jni-jvm = { module = "fr.acinq.secp256k1:secp256k1-kmp-jni-jvm", version.ref = "secp256k1-kmp" }
secp256k1-jni-android = { module = "fr.acinq.secp256k1:secp256k1-kmp-jni-android", version.ref = "secp256k1-kmp" }
```

Create `wallet/build.gradle.kts` (copy `:storage` targets; no SQLDelight):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { }
    jvm()
    android {
        namespace = "io.bluewallet.blueberry.wallet"
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
            implementation(project(":storage"))
            implementation(libs.bitcoin.kmp)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
        }
        jvmMain.dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BitcoinKmpSmokeTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml wallet/build.gradle.kts \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BitcoinKmpSmokeTest.kt
git commit -m "feat: add :wallet module wired to bitcoin-kmp"
```

Only run the commit if the user asked.

---

### Task 2: Types, constants, hex, log

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt`
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt`
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Log.kt`
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Hex.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/HexTest.kt`

**Interfaces:**
- Consumes: none
- Produces: `AddressScriptType`, `WatchWalletKind`, `WatchAddress`, `WatchWallet`, `WatchGaps`, `SendAmount`, `INITIAL_WATCH_COUNT`, `GAP_LIMIT`, `MAX_WATCH_COUNT`, `BIP84_ACCOUNT_PATH`, `WALLET_SECRET_KEY`, `WATCH_EXTERNAL_KEY`, `WATCH_INTERNAL_KEY`, `log`, `scriptHex`, `hexToBytes`, `hexFromBytes`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals

class HexTest {
    @Test
    fun script_hex_and_round_trip() {
        assertEquals("0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2", scriptHex(hexToBytes("0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2")))
        assertEquals(1, hexToBytes("00").size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.HexTest`

Expected: FAIL — `scriptHex` is not defined.

- [ ] **Step 3: Write types and helpers**

`Types.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

enum class AddressScriptType {
    P2PKH,
    P2SH_P2WPKH,
    P2WPKH,
    P2TR,
}

fun AddressScriptType.wireName(): String = when (this) {
    AddressScriptType.P2PKH -> "p2pkh"
    AddressScriptType.P2SH_P2WPKH -> "p2sh-p2wpkh"
    AddressScriptType.P2WPKH -> "p2wpkh"
    AddressScriptType.P2TR -> "p2tr"
}

fun addressScriptTypeFromWire(value: String): AddressScriptType = when (value) {
    "p2pkh" -> AddressScriptType.P2PKH
    "p2sh-p2wpkh" -> AddressScriptType.P2SH_P2WPKH
    "p2wpkh" -> AddressScriptType.P2WPKH
    "p2tr" -> AddressScriptType.P2TR
    else -> error("unsupported script type $value")
}

enum class WatchWalletKind { BIP84, WIF, ADDRESS }

data class WatchAddress(
    val path: String,
    val index: Int,
    val change: Boolean,
    val address: String,
    val scriptPubKey: ByteArray,
    val scriptType: AddressScriptType? = null,
)

data class WatchWallet(
    val kind: WatchWalletKind,
    val secret: String,
    val addresses: List<WatchAddress>,
    val scripts: List<ByteArray>,
)

data class WatchGaps(val external: Int, val internal: Int)

sealed class SendAmount {
    data class Exact(val sats: Long) : SendAmount()
    data object Max : SendAmount()
}

fun WatchAddress.resolvedScriptType(): AddressScriptType = scriptType ?: AddressScriptType.P2WPKH
```

Public Kotlin names stay `WatchAddress`, `WatchWallet`, `WatchGaps`. Enum constants are `P2PKH` / `BIP84` because Kotlin forbids `p2pkh` / `bip84` as enum names. Tests compare `kind` to `WatchWalletKind.BIP84` and `scriptType` to `AddressScriptType.P2WPKH`. That is the Kotlin map of helix3 string unions. Do not leak `wireName` into callers except when a test needs the helix3 label.

`Constants.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

const val BIP84_ACCOUNT_PATH = "m/84'/0'/0'"
const val WALLET_SECRET_KEY = "wallet_secret"
const val WATCH_EXTERNAL_KEY = "watch_external"
const val WATCH_INTERNAL_KEY = "watch_internal"
const val INITIAL_WATCH_COUNT = 100
const val GAP_LIMIT = 100
const val MAX_WATCH_COUNT = 10_000
const val WALLET_BIRTHDAY_HEIGHT_KEY = "wallet_birthday_height"
const val WALLET_BIRTHDAY_PENDING = "pending"
const val BC_UR_PSBT_CAPACITY = 175
```

`Log.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

fun log(scope: String, message: String) {
}
```

`Hex.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

fun scriptHex(script: ByteArray): String = hexFromBytes(script)

fun hexFromBytes(bytes: ByteArray): String = bytes.joinToString("") { b ->
    val v = b.toInt() and 0xff
    val hex = "0123456789abcdef"
    "${hex[v shr 4]}${hex[v and 0x0f]}"
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun outpointKey(txidDisplay: String, vout: Int): String = "$txidDisplay:$vout"

fun prevoutTxidDisplay(inputHash: ByteArray): String = hexFromBytes(inputHash.reversedArray())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.HexTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Types.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Constants.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Log.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Hex.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/HexTest.kt
git commit -m "feat: add wallet types, constants, and hex helpers"
```

---

### Task 3: `isAddressValid` and `watchAddressScriptType`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/IsAddressValid.kt`
- Create: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/TestVectors.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/IsAddressValidTest.kt`

**Interfaces:**
- Consumes: `AddressScriptType`
- Produces: `isAddressValid(address: String): Boolean`, `watchAddressScriptType(address: String): AddressScriptType`

- [ ] **Step 1: Write the failing test**

Create `TestVectors.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

const val ABANDON =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
const val BLUE_ZPUB =
    "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
const val BLUE_EXTERNAL_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
const val BLUE_EXTERNAL_1 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
const val BLUE_INTERNAL_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"
const val BLUE_EXTERNAL_0_SCRIPT = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
const val SEEDSIGNER_ZPUB =
    "zpub6rutAggZJCvkgZg3BAqNGAxCkx1khxCE6g6jyJugMfZ1zgkVdUWSdnzSRpWX1GYVZXCpQFS87BUsvgXXJBpsJVroiHbu4Js2TY69zbWcTNb"
const val SEEDSIGNER_EXTERNAL_0 = "bc1q68y6r45k4kvxe42xl37dgjueg2suqwnh4ze0sr"
const val WIF_BECH32 = "L4vn2KxgMLrEVpxjfLwxfjnPPQMnx42DCjZJ2H7nN4mdHDyEUWXd"
const val ADDR_BECH32 = "bc1q3rl0mkyk0zrtxfmqn9wpcd3gnaz00yv9yp0hxe"
const val WIF_LEGACY = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
const val ADDR_LEGACY = "14YZ6iymQtBVQJk6gKnLCk49UScJK7SH4M"
const val WIF_P2SH = "Ky1vhqYGCiCbPd8nmbUeGfwLdXB1h5aGwxHwpXrzYRfY5cTZPDo4"
const val ADDR_P2SH = "3CKN8HTCews4rYJYsyub5hjAVm5g5VFdQJ"
const val WIF_TAPROOT = "L4PKRVk1Peaar5WuH5LiKfkTygWtFfGrFeH2g2t3YVVqiwpJjMoF"
const val ADDR_TAPROOT = "bc1pm6lqlel3qxefsx0v39nshtghasvvp6ghn3e5hd5q280j5m9h7csqrkzssu"
const val BIP341_TAPROOT = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
const val DEST_LEGACY = "1GX36PGBUrF8XahZEGQqHqnJGW2vCZteoB"
const val GENESIS_P2PKH = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
```

Create `IsAddressValidTest.kt` (helix3 `is-address-valid.test.ts`):

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsAddressValidTest {
    @Test
    fun accepts_mainnet_p2wpkh_p2pkh_p2sh_and_taproot() {
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val root = DeterministicWallet.generate(seed)
        val nativeKey = root.derivePrivateKey("m/84'/0'/0'/0/0").publicKey
        val nestedKey = root.derivePrivateKey("m/49'/0'/0'/0/0").publicKey
        val native = Bitcoin.computeP2WpkhAddress(nativeKey, Block.LivenetGenesisBlock.hash)
        val nested = Bitcoin.computeP2ShOfP2WpkhAddress(nestedKey, Block.LivenetGenesisBlock.hash)
        assertTrue(isAddressValid(native))
        assertTrue(isAddressValid(GENESIS_P2PKH))
        assertTrue(isAddressValid(nested))
        assertTrue(isAddressValid(BIP341_TAPROOT))
    }

    @Test
    fun rejects_garbage_testnet_bad_checksum_and_witness_v2() {
        assertFalse(isAddressValid(""))
        assertFalse(isAddressValid("not-an-address"))
        assertFalse(isAddressValid("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertFalse(isAddressValid("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4x"))
        assertFalse(isAddressValid("bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs"))
    }

    @Test
    fun trims_whitespace_around_a_valid_address() {
        assertTrue(isAddressValid("  $BLUE_EXTERNAL_0  "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.IsAddressValidTest`

Expected: FAIL — `isAddressValid` is not defined.

- [ ] **Step 3: Implement**

Port `/home/bigboss/Code/helix3/src/wallet/is-address-valid.ts`.

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Script

fun isAddressValid(address: String): Boolean {
    val value = address.trim()
    if (value.isEmpty()) return false
    return try {
        if (!value.lowercase().startsWith("bc1")) {
            Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value)
                .isRight
        } else {
            val decoded = Bech32.decodeWitnessAddress(value)
            val version = decoded.second.toInt() and 0xff
            val program = decoded.third
            when (version) {
                0 -> Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value).isRight
                1 -> {
                    if (program.size != 32) return false
                    val compressed = byteArrayOf(2) + program
                    Crypto.isPubKeyValid(compressed)
                }
                else -> false
            }
        }
    } catch (_: Exception) {
        false
    }
}

fun watchAddressScriptType(address: String): AddressScriptType {
    val value = address.trim()
    if (!isAddressValid(value)) throw IllegalArgumentException("invalid mainnet address")
    if (value.lowercase().startsWith("bc1")) {
        val decoded = Bech32.decodeWitnessAddress(value)
        val version = decoded.second.toInt() and 0xff
        val program = decoded.third
        if (version == 0) {
            if (program.size == 20) return AddressScriptType.P2WPKH
            if (program.size == 32) throw IllegalArgumentException("P2WSH watch addresses are unsupported")
            throw IllegalArgumentException("unsupported witness v0 address")
        }
        if (version == 1 && program.size == 32) return AddressScriptType.P2TR
        throw IllegalArgumentException("unsupported witness address")
    }
    val (prefix, _) = Base58Check.decode(value)
    return when (prefix) {
        Base58.Prefix.PubkeyAddress -> AddressScriptType.P2PKH
        Base58.Prefix.ScriptAddress -> AddressScriptType.P2SH_P2WPKH
        else -> throw IllegalArgumentException("unsupported mainnet address version")
    }
}

fun outputScriptFromAddress(address: String): ByteArray {
    val value = address.trim()
    val lower = value.lowercase()
    if (lower.startsWith("bc1")) {
        val decoded = Bech32.decodeWitnessAddress(value)
        val version = decoded.second.toInt() and 0xff
        val program = decoded.third
        if (version == 1 && program.size == 32) {
            return byteArrayOf(0x51, 0x20) + program
        }
    }
    val script = Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value)
        .right
        ?: throw IllegalArgumentException("invalid mainnet address")
    return Script.write(script)
}
```

Check `Either` accessors on this bitcoin-kmp version (`isRight` / `right`). If names differ, use `fold`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.IsAddressValidTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/IsAddressValid.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/TestVectors.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/IsAddressValidTest.kt
git commit -m "feat: port helix3 mainnet address validation"
```

---

### Task 4: Parse and persist `wallet_secret`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Secret.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/SecretTest.kt`

**Interfaces:**
- Consumes: `isAddressValid`, `watchAddressScriptType`, `:storage` `Database.keyValue`
- Produces: `WalletSecretKind`, `ParsedWalletSecret`, `parseWalletSecret`, `decodeWifPrivateKey`, `hasWalletSecret`, `inspectWalletSecret`, `loadWalletSecret`, `saveWalletSecret`

- [ ] **Step 1: Write the failing test**

Port helix3 `parseWalletSecret` cases from `wallet.test.ts`, WIF cases from `wif-wallet.test.ts`, and address cases from `address-watch-wallet.test.ts` (not the receive-store or parse-blocks tests).

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretTest {
    @Test
    fun trims_mnemonic_and_accepts_account_zpub() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.MNEMONIC, ABANDON), parseWalletSecret("  $ABANDON  "))
        assertEquals(ParsedWalletSecret(WalletSecretKind.ZPUB, BLUE_ZPUB), parseWalletSecret(BLUE_ZPUB))
        assertEquals(
            ParsedWalletSecret(WalletSecretKind.MNEMONIC, ABANDON),
            parseWalletSecret(ABANDON.replace(" ", "  ").uppercase()),
        )
    }

    @Test
    fun rejects_invalid_mnemonic_empty_xpub_vpub_master_zpub_french() {
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("not a real mnemonic phrase at all") }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("") }.also {
            assertTrue(it.message!!.contains("empty"))
        }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("   ") }.also {
            assertTrue(it.message!!.contains("empty"))
        }
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val account = DeterministicWallet.generate(seed).derivePrivateKey(BIP84_ACCOUNT_PATH)
        val xpub = account.extendedPublicKey.encode(DeterministicWallet.xpub)
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(xpub) }.also {
            assertTrue(it.message!!.contains("zpub"))
        }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("zprv" + "1".repeat(107)) }.also {
            assertTrue(it.message!!.contains("mainnet account zpub"))
        }
        val vpub = account.extendedPublicKey.encode(DeterministicWallet.vpub)
        assertTrue(vpub.startsWith("vpub"))
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(vpub) }.also {
            assertTrue(it.message!!.contains("mainnet account zpub"))
        }
        val master = DeterministicWallet.generate(seed).extendedPublicKey.encode(DeterministicWallet.zpub)
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(master) }.also {
            assertTrue(it.message!!.contains("account-level"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret(
                "abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abeille",
            )
        }.also { assertTrue(it.message!!.contains("mnemonic")) }
    }

    @Test
    fun kv_round_trip_and_inspect() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(hasWalletSecret(db))
        assertFailsWith<IllegalArgumentException> { loadWalletSecret(db) }
        saveWalletSecret(db, ABANDON)
        assertTrue(hasWalletSecret(db))
        assertEquals(ABANDON, loadWalletSecret(db))
        assertEquals(ABANDON, db.keyValue.get(WALLET_SECRET_KEY))
        assertEquals(WalletSecretInspection.Ok(ABANDON), inspectWalletSecret(db))
        db.keyValue.set(WALLET_SECRET_KEY, "not a real mnemonic phrase at all")
        val bad = inspectWalletSecret(db)
        assertTrue(bad is WalletSecretInspection.Invalid)
        assertTrue((bad as WalletSecretInspection.Invalid).detail.isNotEmpty())
        assertEquals("not a real mnemonic phrase at all", db.keyValue.get(WALLET_SECRET_KEY))
        db.close()
    }

    @Test
    fun accepts_wif_and_rejects_uncompressed_and_testnet() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.WIF, WIF_BECH32), parseWalletSecret("  $WIF_BECH32  "))
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_P2SH).kind)
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_TAPROOT).kind)
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("5JqSfbkoVDrzM5i7PH7939G5fwWVDWmnFTSMbVctAmet3tYMq2S")
        }.also { assertTrue(it.message!!.contains("compressed") || it.message!!.contains("WIF")) }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("KnotAValidWifKeyxxxxxxxxxxx") }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA")
        }.also { assertTrue(it.message!!.contains("mainnet") || it.message!!.contains("testnet")) }
    }

    @Test
    fun accepts_mainnet_addresses_and_rejects_p2wsh() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.ADDRESS, ADDR_BECH32), parseWalletSecret("  $ADDR_BECH32  "))
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_LEGACY).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_P2SH).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_TAPROOT).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(BIP341_TAPROOT).kind)
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
        }.also { assertTrue(it.message!!.contains("invalid mainnet address")) }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("${ADDR_BECH32.substring(0, 8)} ${ADDR_BECH32.substring(8)}")
        }.also { assertTrue(it.message!!.contains("invalid mainnet address")) }
        val p2wsh = p2wshOpTrueAddress()
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(p2wsh) }.also {
            assertTrue(it.message!!.contains("P2WSH") && it.message!!.contains("unsupported"))
        }
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_BECH32).kind)
        val empty = createSqliteDatabase(":memory:")
        assertEquals(WalletSecretInspection.Missing, inspectWalletSecret(empty))
        empty.close()
    }
}

internal fun p2wshOpTrueAddress(): String {
    val script = byteArrayOf(0x51)
    val hash = fr.acinq.bitcoin.Crypto.sha256(script)
    return fr.acinq.bitcoin.Bech32.encodeWitnessAddress("bc", 0, hash)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.SecretTest`

Expected: FAIL — `parseWalletSecret` is not defined.

- [ ] **Step 3: Implement `Secret.kt`**

Port `/home/bigboss/Code/helix3/src/wallet/secret.ts` in order: trim → `zpub` → other xpub-family → WIF candidate → valid address → address-shaped error → BIP39.

```kotlin
enum class WalletSecretKind { MNEMONIC, ZPUB, WIF, ADDRESS }
data class ParsedWalletSecret(val kind: WalletSecretKind, val value: String)
sealed class WalletSecretInspection {
    data object Missing : WalletSecretInspection()
    data class Ok(val value: String) : WalletSecretInspection()
    data class Invalid(val detail: String) : WalletSecretInspection()
}
```

`decodeWifPrivateKey`:

1. Trim. Empty → `invalid WIF`.
2. Starts with `c` or `9` → `only mainnet compressed WIF is supported (not testnet)`.
3. Starts with `5` → `uncompressed WIF is not supported; use compressed WIF`.
4. Base58Check decode. Version must be `Base58.Prefix.SecretKey` (`0x80`). Payload must be 33 bytes ending in `0x01`. Return the first 32 bytes. Any other failure → `invalid WIF`.

`looksLikeWifCandidate`: no whitespace, length 51–52, first char in `5KL9c`.

`looksLikeAddressCandidate`: `bc1`/`tb1`/`bcrt1`, or compact length 26–35 starting with `1`/`3`/`m`/`n`/`2`.

`inspectWalletSecret` maps exceptions to `Invalid(detail)` and does not change the row.

```kotlin
fun parseWalletSecret(raw: String): ParsedWalletSecret {
    val value = raw.trim()
    if (value.isEmpty()) throw IllegalArgumentException("wallet secret is empty")
    if (value.startsWith("zpub")) {
        val decoded = try {
            DeterministicWallet.ExtendedPublicKey.decode(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid zpub")
        }
        if (decoded.second.depth != 3) {
            throw IllegalArgumentException("zpub must be account-level (m/84'/0'/0')")
        }
        return ParsedWalletSecret(WalletSecretKind.ZPUB, value)
    }
    if (Regex("^[xyzvt]p(?:ub|rv)").containsMatchIn(value)) {
        throw IllegalArgumentException("only mainnet account zpub is supported")
    }
    if (looksLikeWifCandidate(value)) {
        decodeWifPrivateKey(value)
        return ParsedWalletSecret(WalletSecretKind.WIF, value)
    }
    if (isAddressValid(value)) {
        watchAddressScriptType(value)
        return ParsedWalletSecret(WalletSecretKind.ADDRESS, value)
    }
    if (looksLikeAddressCandidate(value)) {
        throw IllegalArgumentException("invalid mainnet address")
    }
    val mnemonic = value.replace(Regex("\\s+"), " ").lowercase()
    try {
        MnemonicCode.validate(mnemonic)
    } catch (_: Exception) {
        throw IllegalArgumentException("invalid BIP39 mnemonic")
    }
    return ParsedWalletSecret(WalletSecretKind.MNEMONIC, mnemonic)
}
```

`hasWalletSecret` / `loadWalletSecret` / `saveWalletSecret` take `io.bluewallet.blueberry.storage.Database`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.SecretTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Secret.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/SecretTest.kt
git commit -m "feat: parse and persist wallet_secret like helix3"
```

---

### Task 5: `generateMnemonic12`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.kt`
- Create: `wallet/src/androidMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.android.kt`
- Create: `wallet/src/iosMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.ios.kt`
- Create: `wallet/src/jvmMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.jvm.kt`
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/GenerateMnemonic.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/GenerateMnemonicTest.kt`

**Interfaces:**
- Consumes: `MnemonicCode`
- Produces: `generateMnemonic12(): String`, `fillRandomBytes(dest: ByteArray)`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateMnemonicTest {
    @Test
    fun yields_twelve_valid_english_words() {
        val mnemonic = generateMnemonic12()
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        MnemonicCode.validate(mnemonic)
        assertTrue(words.all { it == it.lowercase() })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.GenerateMnemonicTest`

Expected: FAIL — `generateMnemonic12` is not defined.

- [ ] **Step 3: Implement**

`RandomBytes.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

expect fun fillRandomBytes(dest: ByteArray)
```

JVM / Android: `java.security.SecureRandom().nextBytes(dest)`.

iOS:

```kotlin
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

actual fun fillRandomBytes(dest: ByteArray) {
    dest.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, dest.size.toULong(), pinned.addressOf(0))
        require(status == 0) { "SecRandomCopyBytes failed" }
    }
}
```

`GenerateMnemonic.kt`:

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.MnemonicCode

fun generateMnemonic12(): String {
    val entropy = ByteArray(16)
    fillRandomBytes(entropy)
    return MnemonicCode.toMnemonics(entropy).joinToString(" ")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.GenerateMnemonicTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.kt \
  wallet/src/androidMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.android.kt \
  wallet/src/iosMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.ios.kt \
  wallet/src/jvmMain/kotlin/io/bluewallet/blueberry/wallet/RandomBytes.jvm.kt \
  wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/GenerateMnemonic.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/GenerateMnemonicTest.kt
git commit -m "feat: generate 12-word BIP39 English mnemonics"
```

---

### Task 6: `deriveWatchWallet`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Derive.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/DeriveTest.kt`

**Interfaces:**
- Consumes: `parseWalletSecret`, `decodeWifPrivateKey`, `outputScriptFromAddress`, `watchAddressScriptType`
- Produces: `deriveWatchWallet(secret: String, gaps: WatchGaps? = null): WatchWallet` and overload `deriveWatchWallet(secret: String, gaps: Int)`

- [ ] **Step 1: Write the failing test**

Port BIP84 vectors from `wallet.test.ts`, WIF unwrap from `wif-wallet.test.ts`, and address derive from `address-watch-wallet.test.ts`.

```kotlin
package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeriveTest {
    @Test
    fun abandon_mnemonic_matches_bluewallet_addresses() {
        val wallet = deriveWatchWallet(ABANDON)
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val zpub = DeterministicWallet.generate(seed)
            .derivePrivateKey(BIP84_ACCOUNT_PATH)
            .extendedPublicKey
            .encode(DeterministicWallet.zpub)
        assertEquals(BLUE_ZPUB, zpub)
        assertEquals(INITIAL_WATCH_COUNT * 2, wallet.addresses.size)
        assertEquals(BLUE_EXTERNAL_0, wallet.addresses[0].address)
        assertEquals(BLUE_EXTERNAL_0_SCRIPT, scriptHex(wallet.addresses[0].scriptPubKey))
        assertEquals("m/84'/0'/0'/0/0", wallet.addresses[0].path)
        assertEquals("m/84'/0'/0'/1/0", wallet.addresses[INITIAL_WATCH_COUNT].path)
        assertEquals(BLUE_INTERNAL_0, wallet.addresses[INITIAL_WATCH_COUNT].address)
    }

    @Test
    fun small_gaps_and_zpub_match_mnemonic() {
        val small = deriveWatchWallet(ABANDON, WatchGaps(2, 1))
        assertEquals(listOf(BLUE_EXTERNAL_0, BLUE_EXTERNAL_1, BLUE_INTERNAL_0), small.addresses.map { it.address })
        val fromMnemonic = deriveWatchWallet(ABANDON, WatchGaps(3, 2))
        val fromZpub = deriveWatchWallet(BLUE_ZPUB, WatchGaps(3, 2))
        assertEquals(BLUE_ZPUB, fromZpub.secret)
        assertEquals(fromMnemonic.addresses.map { it.address }, fromZpub.addresses.map { it.address })
        assertEquals(fromMnemonic.addresses.map { it.path }, fromZpub.addresses.map { it.path })
        assertEquals(fromMnemonic.scripts.map { scriptHex(it) }, fromZpub.scripts.map { scriptHex(it) })
        assertEquals(SEEDSIGNER_EXTERNAL_0, deriveWatchWallet(SEEDSIGNER_ZPUB, WatchGaps(1, 0)).addresses[0].address)
        assertEquals(5, deriveWatchWallet(ABANDON, WatchGaps(3, 2)).addresses.size)
        assertEquals(8, deriveWatchWallet(ABANDON, 4).addresses.size)
    }

    @Test
    fun wif_unwraps_four_types() {
        val w = deriveWatchWallet(WIF_BECH32)
        assertEquals(WatchWalletKind.WIF, w.kind)
        assertEquals(4, w.addresses.size)
        assertEquals(ADDR_BECH32, w.addresses.first { it.scriptType == AddressScriptType.P2WPKH }.address)
        assertEquals("1DVNNDU4sooWp6St9baaM8XQC9VYpwVcDi", w.addresses.first { it.scriptType == AddressScriptType.P2PKH }.address)
        assertEquals("3QS6GoKXFCyhTRi7MqQ8vCGp8qxDRyk43J", w.addresses.first { it.scriptType == AddressScriptType.P2SH_P2WPKH }.address)
        assertTrue(w.addresses.first { it.scriptType == AddressScriptType.P2TR }.address.startsWith("bc1p"))
        assertEquals(ADDR_LEGACY, deriveWatchWallet(WIF_LEGACY).addresses.first { it.scriptType == AddressScriptType.P2PKH }.address)
        assertEquals(ADDR_P2SH, deriveWatchWallet(WIF_P2SH).addresses.first { it.scriptType == AddressScriptType.P2SH_P2WPKH }.address)
        assertEquals(ADDR_TAPROOT, deriveWatchWallet(WIF_TAPROOT).addresses.first { it.scriptType == AddressScriptType.P2TR }.address)
        val a = deriveWatchWallet(WIF_BECH32, 1)
        val b = deriveWatchWallet(WIF_BECH32, WatchGaps(500, 500))
        assertEquals(a.addresses.map { it.address }, b.addresses.map { it.address })
    }

    @Test
    fun address_watch_is_one_script() {
        val w = deriveWatchWallet(ADDR_BECH32)
        assertEquals(WatchWalletKind.ADDRESS, w.kind)
        assertEquals(1, w.addresses.size)
        assertEquals(ADDR_BECH32, w.addresses[0].address)
        assertEquals("address/0", w.addresses[0].path)
        assertEquals(AddressScriptType.P2WPKH, w.addresses[0].scriptType)
        assertEquals(scriptHex(outputScriptFromAddress(ADDR_BECH32)), scriptHex(w.addresses[0].scriptPubKey))
        assertEquals(AddressScriptType.P2PKH, deriveWatchWallet(ADDR_LEGACY).addresses[0].scriptType)
        assertEquals(AddressScriptType.P2SH_P2WPKH, deriveWatchWallet(ADDR_P2SH).addresses[0].scriptType)
        assertEquals(AddressScriptType.P2TR, deriveWatchWallet(ADDR_TAPROOT).addresses[0].scriptType)
        assertEquals(1, deriveWatchWallet(ADDR_BECH32, 1).addresses.size)
        assertEquals(1, deriveWatchWallet(ADDR_BECH32, WatchGaps(500, 500)).addresses.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.DeriveTest`

Expected: FAIL — `deriveWatchWallet` is not defined.

- [ ] **Step 3: Implement `Derive.kt`**

Port `/home/bigboss/Code/helix3/src/wallet/derive.ts`.

WIF script order: `P2PKH`, `P2SH_P2WPKH`, `P2WPKH`, `P2TR`. Paths `wif/p2pkh` … `wif/p2tr`.

BIP84: mnemonic → seed → `m/84'/0'/0'` then `m/0/i` and `m/1/i`. zpub → `ExtendedPublicKey.decode` then `derivePublicKey("m/0/i")` / `m/1/i`. Address path string is always `m/84'/0'/0'/{0|1}/{i}`. `scriptType` is `P2WPKH`.

`normalizeGaps`: missing → `{100,100}`; `Int` → both chains; floor and clamp ≥ 0.

Public key from WIF: `PrivateKey(priv).publicKey()`. Taproot uses x-only (`publicKey.value` drop the first byte) with `Bitcoin.computeBIP86Address`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.DeriveTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Derive.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/DeriveTest.kt
git commit -m "feat: derive BIP84, WIF, and address watch scripts"
```

---

### Task 7: Watch gaps

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WatchGapsTest.kt`

**Interfaces:**
- Consumes: `WatchGaps`, KV keys, `:storage` `Database`
- Produces: `saveWatchGaps`, `loadWatchGaps`, `growWatchGapsIfNeeded`

- [ ] **Step 1: Write the failing test**

Port `/home/bigboss/Code/helix3/tests/unit/watch-gaps.test.ts` exactly (defaults 100, clamp 10_000, grow rules).

```kotlin
package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchGapsTest {
    @Test
    fun load_defaults_and_persists() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_EXTERNAL_KEY))
        assertEquals(INITIAL_WATCH_COUNT.toString(), db.keyValue.get(WATCH_INTERNAL_KEY))
        saveWatchGaps(db, WatchGaps(60, 40))
        assertEquals(WatchGaps(60, 40), loadWatchGaps(db))
        db.close()
    }

    @Test
    fun load_clamps_absurd_counts() {
        val db = createSqliteDatabase(":memory:")
        db.keyValue.set(WATCH_EXTERNAL_KEY, "1000000000")
        db.keyValue.set(WATCH_INTERNAL_KEY, "-1")
        assertEquals(WatchGaps(10_000, INITIAL_WATCH_COUNT), loadWatchGaps(db))
        assertEquals("10000", db.keyValue.get(WATCH_EXTERNAL_KEY))
        db.close()
    }

    @Test
    fun grows_when_used_index_in_danger_zone() {
        val r = growWatchGapsIfNeeded(WatchGaps(40, 40), listOf(25), emptyList(), 20)
        assertTrue(r.grew)
        assertEquals(WatchGaps(60, 40), r.gaps)
    }

    @Test
    fun no_grow_below_danger_zone() {
        val r = growWatchGapsIfNeeded(WatchGaps(40, 40), listOf(19), listOf(10), 20)
        assertFalse(r.grew)
        assertEquals(WatchGaps(40, 40), r.gaps)
    }

    @Test
    fun growth_stops_at_cap() {
        val atCap = growWatchGapsIfNeeded(WatchGaps(10_000, 10_000), listOf(9_999), emptyList(), 100)
        assertFalse(atCap.grew)
        val nearCap = growWatchGapsIfNeeded(WatchGaps(9_950, 40), listOf(9_900), emptyList(), 100)
        assertTrue(nearCap.grew)
        assertEquals(WatchGaps(10_000, 40), nearCap.gaps)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WatchGapsTest`

Expected: FAIL — `loadWatchGaps` is not defined.

- [ ] **Step 3: Implement**

Port `/home/bigboss/Code/helix3/src/wallet/watch-gaps.ts`. `growWatchGapsIfNeeded` default `gapLimit` is `GAP_LIMIT`.

```kotlin
data class GrowWatchGapsResult(val gaps: WatchGaps, val grew: Boolean)

fun growWatchGapsIfNeeded(
    gaps: WatchGaps,
    usedExternal: List<Int>,
    usedInternal: List<Int>,
    gapLimit: Int = GAP_LIMIT,
): GrowWatchGapsResult {
    fun bump(n: Int, idxs: List<Int>): Int {
        val start = if (n < gapLimit) 0 else n - gapLimit
        if (idxs.none { it >= start && it < n }) return n
        return minOf(n + gapLimit, MAX_WATCH_COUNT)
    }
    val external = bump(gaps.external, usedExternal)
    val internal = bump(gaps.internal, usedInternal)
    return GrowWatchGapsResult(WatchGaps(external, internal), external != gaps.external || internal != gaps.internal)
}
```

`loadWatchGaps` rewrites KV when a raw value is missing or not the canonical decimal string.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WatchGapsTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/WatchGaps.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WatchGapsTest.kt
git commit -m "feat: load, save, and grow watch gaps"
```

---

### Task 8: `createWallet`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Wallet.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WalletTest.kt`

**Interfaces:**
- Consumes: `parseWalletSecret`, `loadWalletSecret`, `deriveWatchWallet`, `loadWatchGaps`, `saveWatchGaps`, `log`
- Produces: `CreateWalletOptions`, `Wallet`, `createWallet(db, options): Wallet`

- [ ] **Step 1: Write the failing test**

Port `createWallet` cases from `wallet.test.ts`. Drop `openTempFileLog` and all `file.read()` assertions. Keep identity checks: `syncFromDb().grew == false` keeps the same `scripts()` list instance; after growth, script count is 7.

```kotlin
package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WalletTest {
    @Test
    fun loads_kv_secret_and_first_address() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, ABANDON)
        val wallet = createWallet(db)
        assertEquals(WatchGaps(INITIAL_WATCH_COUNT, INITIAL_WATCH_COUNT), wallet.gaps())
        assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().addresses[0].address)
        assertEquals(INITIAL_WATCH_COUNT * 2, wallet.scripts().size)
        db.close()
    }

    @Test
    fun secret_override_and_address_gap_does_not_write_secret() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 3))
        assertEquals(WatchGaps(3, 3), wallet.gaps())
        assertEquals(6, wallet.snapshot().addresses.size)
        assertNull(db.keyValue.get(WALLET_SECRET_KEY))
        assertEquals(WatchGaps(3, 3), loadWatchGaps(db))
        db.close()
    }

    @Test
    fun throws_when_secret_missing_or_invalid() {
        val db = createSqliteDatabase(":memory:")
        assertFailsWith<IllegalArgumentException> { createWallet(db) }.also {
            assertTrue(it.message!!.contains("wallet_secret"))
        }
        db.keyValue.set(WALLET_SECRET_KEY, "not a real mnemonic phrase at all")
        assertFailsWith<IllegalArgumentException> { createWallet(db) }
        db.close()
    }

    @Test
    fun sync_from_db_rederives_only_when_gaps_change() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 2))
        assertEquals(4, wallet.scripts().size)
        val scripts1 = wallet.scripts()
        assertFalse(wallet.syncFromDb().grew)
        assertSame(scripts1, wallet.scripts())
        assertSame(wallet.snapshot(), wallet.refresh())
        saveWatchGaps(db, WatchGaps(5, 2))
        assertTrue(wallet.syncFromDb().grew)
        assertEquals(WatchGaps(5, 2), wallet.gaps())
        assertEquals(7, wallet.scripts().size)
        assertEquals(BLUE_EXTERNAL_0, wallet.snapshot().addresses[0].address)
        db.close()
    }

    @Test
    fun peek_gaps_does_not_change_memory() {
        val db = createSqliteDatabase(":memory:")
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON, addressGap = 2))
        saveWatchGaps(db, WatchGaps(9, 2))
        assertEquals(WatchGaps(9, 2), wallet.peekGaps())
        assertEquals(WatchGaps(2, 2), wallet.gaps())
        assertEquals(4, wallet.scripts().size)
        db.close()
    }

    @Test
    fun zpub_and_wif_from_kv() {
        val zdb = createSqliteDatabase(":memory:")
        saveWalletSecret(zdb, BLUE_ZPUB)
        val zw = createWallet(zdb, CreateWalletOptions(addressGap = 2))
        assertEquals(BLUE_ZPUB, zw.snapshot().secret)
        assertEquals(BLUE_EXTERNAL_0, zw.snapshot().addresses[0].address)
        zdb.close()
        val wdb = createSqliteDatabase(":memory:")
        saveWalletSecret(wdb, WIF_BECH32)
        val ww = createWallet(wdb)
        assertEquals(WatchWalletKind.WIF, ww.snapshot().kind)
        assertEquals(4, ww.snapshot().addresses.size)
        assertEquals(ADDR_BECH32, ww.snapshot().addresses.first { it.scriptType == AddressScriptType.P2WPKH }.address)
        wdb.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WalletTest`

Expected: FAIL — `createWallet` is not defined.

- [ ] **Step 3: Implement**

Port `/home/bigboss/Code/helix3/src/wallet/wallet.ts`. Call `log("wallet", ...)` at the same places. The body of `log` stays empty.

```kotlin
data class CreateWalletOptions(val secret: String? = null, val addressGap: Int? = null)
data class SyncFromDbResult(val grew: Boolean)

interface Wallet {
    fun snapshot(): WatchWallet
    fun scripts(): List<ByteArray>
    fun gaps(): WatchGaps
    fun peekGaps(): WatchGaps
    fun refresh(): WatchWallet
    fun syncFromDb(): SyncFromDbResult
}

fun createWallet(db: io.bluewallet.blueberry.storage.Database, options: CreateWalletOptions = CreateWalletOptions()): Wallet
```

`addressGap`: `max(0, addressGap)` then `saveWatchGaps` both chains. Do not write `wallet_secret` when `options.secret` is set.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WalletTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Wallet.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WalletTest.kt
git commit -m "feat: add createWallet snapshot and gap refresh"
```

---

### Task 9: Wallet birthday

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Birthday.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BirthdayTest.kt`

**Interfaces:**
- Consumes: `:storage` `Database`
- Produces: `markWalletBirthdayPending`, `inspectWalletBirthday`, `maybeFreezeWalletBirthday`, `compactFilterFrom`

- [ ] **Step 1: Write the failing test**

Port `/home/bigboss/Code/helix3/tests/unit/wallet-birthday.test.ts`. Drop log-file asserts.

```kotlin
package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.HeaderWrite
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BirthdayTest {
    @Test
    fun none_pending_freeze_once_garbage_is_none() {
        val db = createSqliteDatabase(":memory:")
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        assertFalse(maybeFreezeWalletBirthday(db, 100))
        markWalletBirthdayPending(db)
        assertEquals(WalletBirthdayInspection.Pending, inspectWalletBirthday(db))
        assertTrue(maybeFreezeWalletBirthday(db, 950_123))
        assertEquals(WalletBirthdayInspection.Ok(950_123), inspectWalletBirthday(db))
        assertFalse(maybeFreezeWalletBirthday(db, 950_000))
        assertFalse(maybeFreezeWalletBirthday(db, 960_000))
        assertEquals(WalletBirthdayInspection.Ok(950_123), inspectWalletBirthday(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "nope")
        assertEquals(WalletBirthdayInspection.None, inspectWalletBirthday(db))
        db.close()
    }

    @Test
    fun compact_filter_from_uses_birthday_floor() {
        val db = createSqliteDatabase(":memory:")
        assertNull(compactFilterFrom(db))
        db.headers.append(
            listOf(
                HeaderWrite(
                    height = 100,
                    hashInternalHex = "aa".repeat(32),
                    header = ByteArray(80),
                ),
            ),
        )
        assertEquals(100, compactFilterFrom(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "150")
        assertEquals(150, compactFilterFrom(db))
        db.keyValue.set(WALLET_BIRTHDAY_HEIGHT_KEY, "80")
        assertEquals(100, compactFilterFrom(db))
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BirthdayTest`

Expected: FAIL — `inspectWalletBirthday` is not defined.

- [ ] **Step 3: Implement**

Port `/home/bigboss/Code/helix3/src/wallet/birthday.ts`. Garbage height (non-int, negative, or `String(height) !== trimmed`) → `None`. Call no-op `log` at the helix3 sites.

```kotlin
sealed class WalletBirthdayInspection {
    data object None : WalletBirthdayInspection()
    data object Pending : WalletBirthdayInspection()
    data class Ok(val height: Int) : WalletBirthdayInspection()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BirthdayTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/Birthday.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BirthdayTest.kt
git commit -m "feat: persist wallet birthday and compact-filter floor"
```

---

### Task 10: Receive addresses

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddress.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddressTest.kt`

**Interfaces:**
- Consumes: `deriveWatchWallet`, `scriptHex`, `outpointKey`, `prevoutTxidDisplay`, bitcoin-kmp `Transaction`
- Produces: `firstUnusedExternalAddress`, `firstUnusedInternalAddress`, `preferredWifReceiveAddress`, `WifReceiveTxRow`

- [ ] **Step 1: Write the failing test**

Port `/home/bigboss/Code/helix3/tests/unit/receive-address.test.ts` and the `preferredWifReceiveAddress` block from `wif-wallet.test.ts`.

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ReceiveAddressTest {
    @Test
    fun first_unused_external_and_internal() {
        val w = deriveWatchWallet(ABANDON, WatchGaps(3, 1))
        val ext0 = firstUnusedExternalAddress(w, emptyList())
        assertEquals(0, ext0!!.index)
        assertFalse(ext0.change)
        assertEquals(BLUE_EXTERNAL_0, ext0.address)
        assertEquals(2, firstUnusedExternalAddress(deriveWatchWallet(ABANDON, WatchGaps(5, 2)), listOf(0, 1, 3))!!.index)
        assertNull(firstUnusedExternalAddress(deriveWatchWallet(ABANDON, WatchGaps(2, 1)), listOf(0, 1)))
        val int0 = firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 3)), emptyList())
        assertEquals(0, int0!!.index)
        assertEquals(true, int0.change)
        assertEquals(1, firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 4)), listOf(0, 2))!!.index)
        assertNull(firstUnusedInternalAddress(deriveWatchWallet(ABANDON, WatchGaps(1, 2)), listOf(0, 1)))
    }

    @Test
    fun preferred_wif_receive_defaults_and_earliest_touch() {
        val w = deriveWatchWallet(WIF_BECH32)
        val native = preferredWifReceiveAddress(w, emptyList())
        assertEquals(AddressScriptType.P2WPKH, native.scriptType)
        assertEquals(ADDR_BECH32, native.address)
        val legacy = w.addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val tap = w.addresses.first { it.scriptType == AddressScriptType.P2TR }
        val fundLegacy = fundingTx(legacy.scriptPubKey, 10_000L, salt = 1)
        val fundTap = fundingTx(tap.scriptPubKey, 10_000L, salt = 2)
        val addr = preferredWifReceiveAddress(
            w,
            listOf(
                WifReceiveTxRow(200, 0, fundTap.tx),
                WifReceiveTxRow(100, 5, fundLegacy.tx),
            ),
        )
        assertEquals(AddressScriptType.P2PKH, addr.scriptType)
    }
}
```

Add helpers `fundingTx(script, valueSats, salt)` and the remaining helix3 WIF cases (same-height `txIndex`, spend-of-known-outpoint) in the same file. Build txs with bitcoin-kmp `Transaction` / `TxIn` / `TxOut` (version 2). Display txid is bitcoin-kmp tx id. Spend input hash is that txid in **internal** order (reverse of display).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.ReceiveAddressTest`

Expected: FAIL — `firstUnusedExternalAddress` is not defined.

- [ ] **Step 3: Implement**

Port `/home/bigboss/Code/helix3/src/wallet/receive-address.ts`. Parse `row.tx` with bitcoin-kmp `Transaction.read`. Walk outputs first, then non-coinbase inputs. Use `scriptHex` / `outpointKey` / `prevoutTxidDisplay`.

Require `wallet.kind == WIF`. Missing native address → `WIF wallet missing native segwit address`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.ReceiveAddressTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddress.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/ReceiveAddressTest.kt
git commit -m "feat: pick unused and preferred WIF receive addresses"
```

---

### Task 11: BIP84 `buildSignedSendTx`

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt`

**Interfaces:**
- Consumes: `parseWalletSecret`, `deriveWatchWallet`, `isAddressValid`, bitcoin-kmp tx/sign
- Produces: `SendInputUtxo`, `BuildSendTxParams`, `BuildSendTxResult`, `buildSignedSendTx`

- [ ] **Step 1: Write the failing BIP84 signed tests**

Port the `buildSignedSendTx` describe from `/home/bigboss/Code/helix3/tests/unit/build-send-tx.test.ts`. Put helpers and every case in `BuildSendTxTest.kt`.

```kotlin
fun abandonWallet() = deriveWatchWallet(ABANDON, WatchGaps(2, 2))

fun utxoAt(wallet: WatchWallet, index: Int = 0) = SendInputUtxo(
    txid = "11".repeat(32),
    vout = 0,
    valueSats = 100_000L,
    scriptPubKey = wallet.addresses.first { !it.change && it.index == index }.scriptPubKey,
)

fun baseParams(
    secret: String = ABANDON,
    wallet: WatchWallet = abandonWallet(),
    utxos: List<SendInputUtxo> = listOf(utxoAt(wallet)),
    amount: SendAmount = SendAmount.Exact(50_000L),
    feeRate: Double = 1.0,
) = BuildSendTxParams(
    secret = secret,
    wallet = wallet,
    utxos = utxos,
    toAddress = BLUE_EXTERNAL_1,
    amountSats = amount,
    feeRateSatPerVb = feeRate,
    changeAddress = BLUE_INTERNAL_0,
)
```

Cases (same numbers as helix3):

1. 50_000 @ 10 sat/vB: signed, 1 in / 2 out, dest amount 50_000, `round(fee/vsize) >= 10` and `fee/vsize <= 11`
2. 10_000 @ 1 sat/vB: `feeSats == vsize.toLong()`
3. 50_000 @ 0.5 and 1.5: `feeSats == ceil(rate * vsize)`
4. send-max @ 1.5: `changeSats == 0`, 1 output to `BLUE_EXTERNAL_1`, amount `100_000 - fee`
5. two UTXOs send-max (100_000 + 80_000): 2 inputs, 1 output
6. 600 sat send-max @ 1: throws message containing `insufficient`
7. extra 30 sat UTXO: throws `uneconomical` for max and for 50_000
8. dest === `BLUE_INTERNAL_0` for 10_000 and 50_000: two outputs, one equals the payment, `changeSats == 100_000 - amount - fee`, `fee == ceil(1.5 * vsize)`
9. zpub secret → `mnemonic`/`WIF`; empty utxos → `no UTXOs`; amount 0 → `amount`; fee 0 and `Double.POSITIVE_INFINITY` → `fee rate`; 200_000 → `insufficient`; dest `not-an-address` → `invalid` + `address`

Parse signed hex with bitcoin-kmp. Do not use scure.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: FAIL — `buildSignedSendTx` is not defined.

- [ ] **Step 3: Implement the signed BIP84 path**

Port `/home/bigboss/Code/helix3/src/wallet/build-send-tx.ts` for mnemonic only first.

```kotlin
data class SendInputUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val scriptPubKey: ByteArray,
    val nonWitnessUtxo: ByteArray? = null,
)
data class BuildSendTxParams(
    val secret: String,
    val wallet: WatchWallet,
    val utxos: List<SendInputUtxo>,
    val toAddress: String,
    val amountSats: SendAmount,
    val feeRateSatPerVb: Double,
    val changeAddress: String,
)
data class BuildSendTxResult(
    val kind: String = "signed",
    val txHex: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
)
```

Rules that must hold:

1. Use **all** caller UTXOs. If fee math would drop one, throw `some selected UTXOs are uneconomical at this fee rate`.
2. Integer select uses `ceil(feeRateSatPerVb)` sat/vB. Then move excess into change so the fee becomes `ceil(feeRateSatPerVb * vsize)`.
3. When dest === change, do not add the excess to the payment output.
4. Send-max: one output to `toAddress`, `changeSats = 0`.
5. Dust / leftover 0 after fee → `insufficient funds for amount and fee`.
6. After sign, return the **signed** `tx.fee` equivalent (`inputSum - outputSum`) and signed `vsize`. Those must match the tests (`fee == ceil(rate * vsize)`).

vsize must match a signed p2wpkh tx the same way scure does, or the fee tests fail. Estimate with standard weights, then read `weight/4` from the signed tx (round up). Tune the unsigned template (sequence, witness placeholder) until signed vsize matches helix3 for the abandon 100_000 → 50_000 @ 10 sat/vB case.

Signing: derive `PrivateKey` at each `WatchAddress.path` from the mnemonic master. Sign each p2wpkh input. Finalize witnesses.

`buildSignedSendTx` on zpub/address throws `signing requires a mnemonic or WIF wallet secret`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt
git commit -m "feat: sign BIP84 sends with helix3 fee rules"
```

---

### Task 12: Unsigned PSBT (`buildSend` / zpub)

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt`
- Modify: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt`

**Interfaces:**
- Consumes: Task 11 draft builder
- Produces: `buildUnsignedSendPsbt`, `buildSend`

- [ ] **Step 1: Write the failing PSBT tests**

Add the `buildSend / unsigned PSBT` cases from helix3:

- zpub → `kind == psbt`, hex starts with `70736274ff`, `changeSats > 0`, `buildUnsignedSendPsbt` matches `buildSend`
- mnemonic `buildSend` → `kind == signed`
- zpub send-max → one output, `changeSats == 0`
- zpub BIP32 origin: fingerprint is the **account** zpub fingerprint; path is `[0, 0]` for external index 0

Read PSBT with bitcoin-kmp `Psbt.read`. Compare fingerprint as `UInt` / `Long` masked to 32 bits (`and 0xffffffff`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: FAIL — `buildSend` is not defined (or PSBT assertions fail).

- [ ] **Step 3: Implement**

`buildSend`: mnemonic/WIF → `buildSignedSendTx`; else `buildUnsignedSendPsbt`. Address + not-max forces `changeAddress` to the watched address.

```kotlin
data class BuildSendPsbtResult(
    val kind: String = "psbt",
    val psbtHex: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
)
sealed class BuildSendResult
data class SignedSendResult(
    val txHex: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
) : BuildSendResult()
data class PsbtSendResult(
    val psbtHex: String,
    val feeSats: Long,
    val vsize: Int,
    val changeSats: Long,
) : BuildSendResult()
```

Tests branch on `is SignedSendResult` / `is PsbtSendResult`. That is the Kotlin map of helix3 `kind: "signed" | "psbt"`.

Fill PSBT input `bip32Derivation` for HD: pubkey + account fingerprint + relative path (`m/0/i` or `m/1/i` for zpub; full path for mnemonic).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/BuildSendTxTest.kt
git commit -m "feat: build unsigned zpub PSBTs"
```

---

### Task 13: WIF and address `buildSend`

**Files:**
- Modify: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WifSendTest.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/AddressWatchSendTest.kt`

**Interfaces:**
- Consumes: Task 11–12 builder
- Produces: same functions; WIF signs all four script types; address watches emit script-only PSBTs

- [ ] **Step 1: Write the failing tests**

Port WIF signing from `wif-wallet.test.ts` and address `buildSend` from `address-watch-wallet.test.ts` (skip receive-store and parse-blocks).

WIF: native / wrapped / legacy+nonWitnessUtxo / taproot send-max @ 4 sat/vB / mixed four inputs / missing nonWitnessUtxo throws `legacy p2pkh input requires nonWitnessUtxo (previous transaction)`.

Address: nested PSBT has no redeemScript; legacy PSBT keeps nonWitnessUtxo; `buildSend` overrides a wrong change address to the watched one; `buildSignedSendTx` throws; uppercase bech32 fractional fee; non-max to watched address throws `cannot send back to the watched address` on both `buildSend` and `buildUnsignedSendPsbt`; native PSBT drops nonWitnessUtxo; send-max one output; taproot PSBT has no `tapInternalKey`.

Build funding txs with bitcoin-kmp, not bitcoinjs.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WifSendTest --tests io.bluewallet.blueberry.wallet.AddressWatchSendTest`

Expected: FAIL on the new assertions.

- [ ] **Step 3: Implement WIF and address branches**

Port the rest of helix3 `build-send-tx.ts`:

- WIF: sign once with the WIF private key for every input type.
- Address: for p2tr / p2sh-p2wpkh fee estimate only, inject placeholder `tapInternalKey` / redeemScript, then restore script-only inputs on the exported PSBT.
- Estimation pubkey is secp256k1 of 31 zero bytes + `0x01`, compressed (same as helix3).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.WifSendTest --tests io.bluewallet.blueberry.wallet.AddressWatchSendTest --tests io.bluewallet.blueberry.wallet.BuildSendTxTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/BuildSendTx.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/WifSendTest.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/AddressWatchSendTest.kt
git commit -m "feat: sign WIF sends and build address-watch PSBTs"
```

---

### Task 14: BC-UR `crypto-psbt` fragments

**Files:**
- Create: `wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt`
- Test: `wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUrTest.kt`

**Interfaces:**
- Consumes: `buildUnsignedSendPsbt`
- Produces: `encodeCryptoPsbtUrFragments(psbt: ByteArray, capacity: Int = 175): List<String>` and hex-string overload

- [ ] **Step 1: Write the failing test**

Port `/home/bigboss/Code/helix3/tests/unit/encode-psbt-ur.test.ts`:

```kotlin
package io.bluewallet.blueberry.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodePsbtUrTest {
    @Test
    fun encodes_unsigned_psbt_as_ur_crypto_psbt_and_round_trips() {
        val wallet = deriveWatchWallet(BLUE_ZPUB, WatchGaps(2, 1))
        val recv = wallet.addresses.first { !it.change }
        val dest = wallet.addresses.first { !it.change && it.index == 1 }
        val change = wallet.addresses.first { it.change }
        val psbtHex = buildUnsignedSendPsbt(
            BuildSendTxParams(
                secret = BLUE_ZPUB,
                wallet = wallet,
                utxos = listOf(
                    SendInputUtxo(
                        txid = "11".repeat(32),
                        vout = 0,
                        valueSats = 100_000L,
                        scriptPubKey = recv.scriptPubKey,
                    ),
                ),
                toAddress = dest.address,
                amountSats = SendAmount.Exact(50_000L),
                feeRateSatPerVb = 10.0,
                changeAddress = change.address,
            ),
        ).psbtHex
        val parts = encodeCryptoPsbtUrFragments(psbtHex, BC_UR_PSBT_CAPACITY)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.lowercase().startsWith("ur:crypto-psbt/") })
        assertEquals(psbtHex.lowercase(), hexFromBytes(decodeCryptoPsbtUrFragments(parts)))
    }
}
```

`decodeCryptoPsbtUrFragments` may be `internal` in `EncodePsbtUr.kt`. It must invert the encoder. No Maven UR library.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.EncodePsbtUrTest`

Expected: FAIL — `encodeCryptoPsbtUrFragments` is not defined.

- [ ] **Step 3: Implement BC-UR v2 `crypto-psbt`**

Match BlueWallet / Keystone `CryptoPSBT.toUREncoder(capacity)`:

1. PSBT hex → bytes (or take `ByteArray`).
2. CBOR-wrap as a byte string (`0x58`/`0x59`/`0x5a` + length + payload).
3. UR type `crypto-psbt`.
4. If the bytewords body fits in one part at `capacity`, emit one `ur:crypto-psbt/<bytewords>`.
5. Else fountain-code multi-part `ur:crypto-psbt/<seq>-<count>/<bytewords>` using BCR-2020-005 / BCR-2020-012 (CRC32, SHA256, Xoshiro256**, bytewords).
6. Return `fragmentsLength` parts, one per `nextPart()`, same as helix3.

Keep CRC32, bytewords, fountain, and UR encode/decode in `EncodePsbtUr.kt` (or private files in the same package). Do not add a dependency.

Implement decode in the same file so the test can round-trip.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet:jvmTest --tests io.bluewallet.blueberry.wallet.EncodePsbtUrTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wallet/src/commonMain/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUr.kt \
  wallet/src/commonTest/kotlin/io/bluewallet/blueberry/wallet/EncodePsbtUrTest.kt
git commit -m "feat: encode crypto-psbt UR fragments"
```

---

### Task 15: Wire `:shared` and run the full suite

**Files:**
- Modify: `shared/build.gradle.kts`

**Interfaces:**
- Consumes: `:wallet`
- Produces: `:shared` compiles against `:wallet`

- [ ] **Step 1: Write a shared compile check**

In `shared/src/commonMain/kotlin` do **not** add UI. Only add the Gradle dependency. No new shared test is required if `:wallet` tests cover the API. If `:shared` will not compile without an import, skip a shared test.

- [ ] **Step 2: Add the dependency**

In `shared/build.gradle.kts` `commonMain.dependencies`:

```kotlin
implementation(project(":wallet"))
```

- [ ] **Step 3: Run the full wallet suites**

Run:

```bash
./gradlew :wallet:jvmTest
./gradlew :wallet:testAndroidHostTest
./gradlew :shared:jvmTest
```

Expected: all PASS. Do not add UI tests. Do not change Click me.

If Android host fails to load secp256k1, confirm `androidMain` has `libs.secp256k1.jni.android`. If iOS `SecRandomCopyBytes` needs extra linker flags, add them only on the iOS framework that first calls `generateMnemonic12`.

- [ ] **Step 4: Confirm no ACINQ types leak**

Search `wallet/src/commonMain` public signatures. `fun` / `data class` / `interface` used by callers must not mention `fr.acinq`. `implementation` deps stay non-`api`.

- [ ] **Step 5: Commit**

```bash
git add shared/build.gradle.kts
git commit -m "feat: depend on :wallet from :shared"
```

Only run the commit if the user asked.