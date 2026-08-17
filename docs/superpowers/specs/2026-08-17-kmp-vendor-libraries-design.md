# KMP vendor libraries (git submodules)

Date: 2026-08-17  
Status: approved (conversation)

## Goal

Wire the four already-ported Kotlin Multiplatform libraries into `:shared` so later Blueberry port work can call them. Consume them from Git, not from Maven Central or npm.

## Non-goals

- Port helix3 modules, sync, wallet, or UI
- Publish these libraries
- Add Maven, JitPack, GitHub Packages, or `includegit`
- Change app targets (Android, iOS, desktop JVM stay as they are)

## Libraries

| Submodule path | Git remote | Composite module name |
| --- | --- | --- |
| `vendor/bitcoin-headers.kmp` | `https://github.com/GladosBlueWallet/bitcoin-headers.kmp.git` | `org.bitcoin.kmp:bitcoin-headers` |
| `vendor/bip324.kmp` | `https://github.com/GladosBlueWallet/bip324.kmp.git` | `org.bitcoin.kmp:bip324` |
| `vendor/bip158.kmp` | `https://github.com/GladosBlueWallet/bip158.kmp.git` | `bip158:bip158` |
| `vendor/bip157.kmp` | `https://github.com/GladosBlueWallet/bip157.kmp.git` | `org.bitcoin.kmp:bip157` |

Pin each submodule to the `master` tip at implementation time. Record the SHA in this repo’s gitlink.

`bip158` uses group `bip158`, not `org.bitcoin.kmp`. Keep that name. Do not invent a different coordinate.

Each remote is a Gradle project with `include(":library")`. Composite resolution uses project `:library` inside that included build.

## Layout

```
vendor/
  bitcoin-headers.kmp/   # git submodule
  bip324.kmp/            # git submodule
  bip158.kmp/            # git submodule
  bip157.kmp/            # git submodule
```

Commit `.gitmodules` and the four gitlinks. Do not gitignore `vendor/`. Do not vendor build outputs; each submodule keeps its own ignore rules.

Clone / update:

```bash
git submodule update --init
```

README must show that command next to the existing run instructions.

## Build wiring

`settings.gradle.kts` calls `includeBuild` for each `vendor/*` directory.

Each `includeBuild` substitutes the catalog module name with that build’s `:library` project:

- `org.bitcoin.kmp:bitcoin-headers` → `:library`
- `org.bitcoin.kmp:bip324` → `:library`
- `org.bitcoin.kmp:bip157` → `:library`
- `bip158:bip158` → `:library`

If a vendor directory has no `settings.gradle.kts`, settings fail before configuration. The error text must tell the user to run `git submodule update --init`.

`:shared` `commonMain` depends on all four catalog entries (`implementation`). Android, iOS, and desktop keep taking Bitcoin code only through `:shared`. Do not add these libraries to `androidApp` or `desktopApp`.

Version catalog (`gradle/libs.versions.toml`) lists:

- `org.bitcoin.kmp:bitcoin-headers:0.0.1`
- `org.bitcoin.kmp:bip324:0.0.1`
- `org.bitcoin.kmp:bip157:0.0.1`
- `bip158:bip158:0.0.1`

`includeBuild` replaces those names with source. The `0.0.1` version is a label only.

## Check

Add one `commonTest` that imports one public symbol from each library and uses it so the compiler cannot drop the import. Assert these stable facts:

- `MAINNET_HEADER_CONSENSUS.checkpoint.height == 665_280L`
- `Networks.mainnet.defaultPort == 8333`
- `NODE_COMPACT_FILTERS == 64` (`1 shl 6`)
- `hexToBytes("00").size == 1`

The test must not start a socket or read the network.

Pass on the targets this repo already tests:

- `./gradlew :shared:jvmTest`
- `./gradlew :shared:testAndroidHostTest`

iOS simulator tests stay optional on Linux.

## Error handling

| Case | Behavior |
| --- | --- |
| Submodule not initialized | Settings fail with the init command in the message |
| Wrong commit / broken library | Normal Gradle compile or test failure |
| AGP mismatch (app `9.0.1`, libraries `9.1.0`) | Each included build uses its own plugins. If configuration fails, fix the app AGP to `9.1.0` so it matches the libraries. Do not patch vendor source. |

Kotlin is already `2.4.10` on both sides. Do not change it in this slice.

## Out of scope details

Gradle, the Kotlin plugin, AGP, and transitive library deps (for example `org.kotlincrypto.hash:sha2`) still resolve from Maven Central. This slice only keeps **our** four libraries off a package registry.

## Success

- Four submodules exist under `vendor/` and are pinned by SHA
- `:shared` compiles against all four libraries
- The new `commonTest` passes on JVM and Android host
- README documents `git submodule update --init`
- No Maven coordinate for these four libraries is fetched from a remote repo
