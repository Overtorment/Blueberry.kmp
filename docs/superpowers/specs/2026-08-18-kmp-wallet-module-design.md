# KMP wallet module

Date: 2026-08-18  
Status: approved (conversation)

## Goal

Add a `:wallet` Kotlin Multiplatform module. It ports helix3 `src/wallet` with the same public names and behaviour.

Callers create a watch wallet from a mnemonic, account `zpub`, compressed mainnet WIF, or mainnet address. They derive scripts, persist gaps and birthday in KV, and build a signed send or an unsigned PSBT the same way helix3 does.

## Non-goals

- Port TUI, onboarding UI, send-context, or Click me
- Port `parse-blocks`, `filters-matching`, or the message bus
- Port helix3 file logging (real logger comes later)
- A public coin-select helper (helix3 keeps that logic inside `build-send-tx`)
- A new vendor git submodule for Bitcoin or UR
- Open helix3 `.sqlite` files
- Change `:storage` schema or repository names

## Stack

- New Gradle module `:wallet`
- Targets: Android, iOS (`iosArm64`, `iosSimulatorArm64`), desktop JVM (same as `:storage`)
- Package: `io.bluewallet.blueberry.wallet`
- `:wallet` depends on `:storage`
- `:wallet` `implementation` of Maven `fr.acinq.bitcoin:bitcoin-kmp:0.31.0`
- secp256k1 JNI (same version bitcoin-kmp 0.31.0 uses: `0.23.0`):
  - JVM: `fr.acinq.secp256k1:secp256k1-kmp-jni-jvm`
  - Android: `fr.acinq.secp256k1:secp256k1-kmp-jni-android`
  - iOS uses the native artifact that bitcoin-kmp already pulls
- Do not change Kotlin `2.4.10`
- `:shared` depends on `:wallet`

ACINQ types (`ByteVector`, `DeterministicWallet`, `PrivateKey`, `Psbt`) stay inside `:wallet`. They are not part of the public API.

## Public API

Behaviour source: helix3 `src/wallet/*.ts`. Kotlin names match those exports.

| helix3 file | Kotlin file |
| --- | --- |
| `types.ts` | `Types.kt` |
| `secret.ts` | `Secret.kt` |
| `derive.ts` | `Derive.kt` |
| `watch-gaps.ts` | `WatchGaps.kt` |
| `wallet.ts` | `Wallet.kt` |
| `birthday.ts` | `Birthday.kt` |
| `is-address-valid.ts` | `IsAddressValid.kt` |
| `receive-address.ts` | `ReceiveAddress.kt` |
| `generate-mnemonic.ts` | `GenerateMnemonic.kt` |
| `build-send-tx.ts` | `BuildSendTx.kt` |
| `encode-psbt-ur.ts` | `EncodePsbtUr.kt` |

`log(scope, message)` exists as a no-op in `Log.kt`. Call sites stay. No file I/O.

Gap defaults match helix3 `config`: `initialWatchCount = 100`, `gapLimit = 100`. Watch cap is `10_000`.

`createWallet(db, options)` takes `:storage` `Database` (it already has `keyValue`). Tests use `createSqliteDatabase(":memory:")`.

`compactFilterFrom(db)` takes `:storage` `Database` (needs `keyValue` and `headers.minHeight()`).

## Type map

| helix3 | Kotlin |
| --- | --- |
| `Uint8Array` | `ByteArray` |
| `bigint` sats / fee | `Long` |
| `number` index / vsize / height | `Int` |
| `number` `feeRateSatPerVb` | `Double` |
| `string \| null` | `String?` |
| `amountSats: bigint \| "max"` | `SendAmount` = `Exact(sats: Long)` or `Max` |
| `"p2pkh" \| "p2sh-p2wpkh" \| "p2wpkh" \| "p2tr"` | `AddressScriptType` enum, same labels |
| `"bip84" \| "wif" \| "address"` | `WatchWalletKind` enum, same labels |

`WatchAddress.scriptType` is optional. Missing means `p2wpkh` (same as helix3).

## Units

| Unit | Role | Depends on |
| --- | --- | --- |
| `Types` | `WatchWallet`, `WatchAddress`, script and kind enums | none |
| `Secret` | Parse / load / save `wallet_secret` | bitcoin-kmp, `IsAddressValid` |
| `Derive` | BIP84 / WIF / address → watch scripts | `Secret`, bitcoin-kmp |
| `WatchGaps` | Load / save / grow watch windows | KV, gap constants |
| `Wallet` | `createWallet`: snapshot, scripts, gaps, refresh, `syncFromDb` | `Secret`, `Derive`, `WatchGaps` |
| `Birthday` | Pending / freeze / compact-filter floor | KV + `headers.minHeight()` |
| `IsAddressValid` | Mainnet check and watch script type | bitcoin-kmp |
| `ReceiveAddress` | First unused ext/int; WIF preferred address from txs | `Types`, bitcoin-kmp tx parse |
| `GenerateMnemonic` | 12 English words from 16 random bytes | bitcoin-kmp BIP39 |
| `BuildSendTx` | `buildSend` / signed tx / unsigned PSBT | `Secret`, `IsAddressValid`, bitcoin-kmp |
| `EncodePsbtUr` | BC-UR v2 `crypto-psbt` fragments, capacity `175` | PSBT bytes |
| `Log` | no-op `log(scope, message)` | none |

`ReceiveAddress` needs `scriptHex`, `outpointKey`, and `prevoutTxidDisplay`. Those helpers live in `:wallet`. Do not port `src/parse`.

Do not export a select-UTXO helper. `BuildSendTx` uses every UTXO the caller passed (helix3 `selectUTXO` strategy `"all"`).

## Data flow

**Create.** `createWallet` reads `wallet_secret` or `options.secret`. It parses the secret. If `addressGap` is set, it writes both gaps to that count (floor, not below 0). Then it loads gaps and derives.

**Refresh.** `syncFromDb` / `refresh` read gaps again. They re-derive only when a gap changed. `peekGaps` reads the DB and does not change memory.

**Birthday.** `markWalletBirthdayPending` writes `pending`. `maybeFreezeWalletBirthday(height)` writes the height only when status is pending. `compactFilterFrom` returns `max(birthday, headerMin)` when birthday is `ok`, else `headerMin`. Missing headers → `null`.

**Send.** The caller passes the UTXO list. `buildSend` consumes all of them. Mnemonic or WIF → signed tx hex. zpub or address → unsigned PSBT. Address watch (not max) forces change to the watched address.

**UR.** `encodeCryptoPsbtUrFragments` takes PSBT bytes or hex. It returns `ur:crypto-psbt/…` parts. Capacity default is `175`. Implement encode (and test decode) inside `:wallet`. No extra Maven UR library. No new submodule.

## Secret rules

Mainnet only. After trim:

1. Starts with `zpub` → account-level BIP84 (`depth == 3`). SLIP-0132 versions: private `0x04b2430c`, public `0x04b24746`.
2. Other `x/y/z/v/t` + `pub`/`prv` → reject.
3. WIF-shaped token (`5KL9c`, length 51–52, no whitespace) → decode compressed mainnet WIF.
4. Valid mainnet address → address watch (not P2WSH).
5. Else English BIP39 mnemonic (collapse whitespace, lower case).

WIF: reject uncompressed (`5…`) and testnet (`c…` / `9…`).

BIP84 derive: mnemonic seed → `m/84'/0'/0'/{0|1}/i` → `p2wpkh`. zpub: account key → `{0|1}/i` → same scripts. WIF: four scripts in order p2pkh, p2sh-p2wpkh, p2wpkh, p2tr. Address: one script. Gaps do not change WIF or address watches.

`inspectWalletSecret`: `missing` / `ok` / `invalid`. Invalid leaves the KV row as-is.

## Send rules

Match helix3 `build-send-tx.ts`:

- Fee with change: `ceil(feeRateSatPerVb * vsize)` after integer-rate select, excess goes to change.
- Send-max: one output, `changeSats = 0`.
- If select drops a UTXO: throw uneconomical (do not sign a subset).
- Legacy p2pkh needs `nonWitnessUtxo`.
- Address watch: placeholder metadata only for fee estimate (p2tr / p2sh-p2wpkh); restore script-only inputs on the PSBT.
- Non-max address send to the watched address is rejected.
- `buildSignedSendTx` rejects zpub and address secrets.

## Error messages

Throw these helix3 strings:

| Case | Message |
| --- | --- |
| Empty secret | `wallet secret is empty` |
| Bad mnemonic | `invalid BIP39 mnemonic` |
| Bad zpub | `invalid zpub` |
| zpub not account | `zpub must be account-level (m/84'/0'/0')` |
| Other extended key | `only mainnet account zpub is supported` |
| Bad WIF | `invalid WIF` |
| Uncompressed WIF | `uncompressed WIF is not supported; use compressed WIF` |
| Testnet WIF | `only mainnet compressed WIF is supported (not testnet)` |
| Bad address | `invalid mainnet address` |
| P2WSH watch | `P2WSH watch addresses are unsupported` |
| Missing KV secret | `wallet_secret missing` |
| Sign with zpub/address | `signing requires a mnemonic or WIF wallet secret` |
| No UTXOs | `no UTXOs selected` |
| Bad amount | `amount must be positive` |
| Bad fee | `fee rate must be positive` |
| Bad dest | `invalid destination address` |
| Bad change | `invalid change address` |
| Not enough / dust max | `insufficient funds for amount and fee` |
| Subset of UTXOs | `some selected UTXOs are uneconomical at this fee rate` |
| Legacy p2pkh without prev tx | `legacy p2pkh input requires nonWitnessUtxo (previous transaction)` |
| Address send to self (not max) | `cannot send back to the watched address` |

`isAddressValid` returns `false`. It does not throw.

`inspectWalletBirthday` treats non-integer / negative / non-canonical height text as `none`. `maybeFreezeWalletBirthday` returns `false` when not pending or height is not a non-negative integer.

## Random

`generateMnemonic12` fills 16 bytes from a platform CSPRNG (`expect`/`actual`), then BIP39 English. Tests check 12 valid words. They do not spy on `getRandomValues`.

## Testing

`commonTest` in `:wallet`. KV tests use `createSqliteDatabase(":memory:")`.

Port:

- `wallet.test.ts` — drop file-log reads and the `openTempFileLog` harness
- `wallet-birthday.test.ts`
- `watch-gaps.test.ts`
- `wif-wallet.test.ts`
- `address-watch-wallet.test.ts` except the receive-store test and the parse-blocks gap test
- `build-send-tx.test.ts`
- `receive-address.test.ts`
- `generate-mnemonic.test.ts` — 12 valid English words only
- `is-address-valid.test.ts`
- `encode-psbt-ur.test.ts` — encode then decode; PSBT bytes must match

Do not port `wallet-secret-modules.test.ts`, TUI tests, `send-context`, or `fit-ur-qr`.

Pass:

- `./gradlew :wallet:jvmTest`
- `./gradlew :wallet:testAndroidHostTest`

iOS simulator tests stay optional on Linux.

Do not add UI tests. Do not change Click me.

## Success

- `:wallet` compiles on the app targets
- Public names and errors match helix3 `src/wallet`
- Ported tests pass on JVM and Android host
- No ACINQ type appears in the public `:wallet` API
- No new vendor submodule
