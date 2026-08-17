# KMP vendor libraries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `bitcoin-headers`, `bip324`, `bip158`, and `bip157` into `:shared` from git submodules so later Blueberry port work can call them.

**Architecture:** Four GitHub repos live under `vendor/` as git submodules. `settings.gradle.kts` `includeBuild`s each tree and substitutes the catalog Maven names onto that build’s `:library` project. `:shared` `commonMain` depends on the catalog entries. A `commonTest` imports one symbol from each library.

**Tech Stack:** Gradle 9.1, Kotlin 2.4.10, Compose Multiplatform, git submodules, GladosBlueWallet `*.kmp` libraries.

## Global Constraints

- Consume the four libraries from Git, not from Maven Central, JitPack, GitHub Packages, or `includegit`.
- Submodule paths and remotes: `vendor/bitcoin-headers.kmp` → `https://github.com/GladosBlueWallet/bitcoin-headers.kmp.git`; `vendor/bip324.kmp` → `https://github.com/GladosBlueWallet/bip324.kmp.git`; `vendor/bip158.kmp` → `https://github.com/GladosBlueWallet/bip158.kmp.git`; `vendor/bip157.kmp` → `https://github.com/GladosBlueWallet/bip157.kmp.git`.
- Composite module names: `org.bitcoin.kmp:bitcoin-headers`, `org.bitcoin.kmp:bip324`, `bip158:bip158`, `org.bitcoin.kmp:bip157`. Keep `bip158` group `bip158`. Do not invent a different coordinate.
- Pin each submodule to the `master` tip at implementation time. Record the SHA in this repo’s gitlink.
- Each remote is a Gradle project with `include(":library")`. Composite resolution uses project `:library`.
- Do not gitignore `vendor/`. Do not vendor build outputs. Do not patch vendor source.
- `:shared` `commonMain` uses `implementation` for all four catalog entries. Do not add these libraries to `androidApp` or `desktopApp`.
- Catalog versions are `0.0.1` labels only: `org.bitcoin.kmp:bitcoin-headers:0.0.1`, `org.bitcoin.kmp:bip324:0.0.1`, `org.bitcoin.kmp:bip157:0.0.1`, `bip158:bip158:0.0.1`.
- If a vendor directory has no `settings.gradle.kts`, settings fail with `git submodule update --init` in the message.
- Test asserts: `MAINNET_HEADER_CONSENSUS.checkpoint.height == 665_280L`; `Networks.mainnet.defaultPort == 8333`; `NODE_COMPACT_FILTERS == 64`; `hexToBytes("00").size == 1`. No sockets. No network I/O.
- Pass `./gradlew :shared:jvmTest` and `./gradlew :shared:testAndroidHostTest`. iOS simulator tests stay optional on Linux.
- Kotlin stays `2.4.10`. If AGP configuration fails, set app AGP to `9.1.0`. Do not patch vendor source.
- Do not port helix3 modules, sync, wallet, or UI. Do not change app targets.

---

### Task 1: Vendor git submodules

**Files:**
- Create: `.gitmodules`
- Create: `vendor/bitcoin-headers.kmp` (gitlink)
- Create: `vendor/bip324.kmp` (gitlink)
- Create: `vendor/bip158.kmp` (gitlink)
- Create: `vendor/bip157.kmp` (gitlink)

**Interfaces:**
- Consumes: none
- Produces: four checked-out Gradle trees, each with `settings.gradle.kts` and project `:library`

- [ ] **Step 1: Add the four submodules**

From the repo root (`/home/bigboss/Code/Blueberry.kmp`):

```bash
git submodule add https://github.com/GladosBlueWallet/bitcoin-headers.kmp.git vendor/bitcoin-headers.kmp
git submodule add https://github.com/GladosBlueWallet/bip324.kmp.git vendor/bip324.kmp
git submodule add https://github.com/GladosBlueWallet/bip158.kmp.git vendor/bip158.kmp
git submodule add https://github.com/GladosBlueWallet/bip157.kmp.git vendor/bip157.kmp
```

Each command checks out that repo’s `master` tip and records the SHA as a gitlink.

- [ ] **Step 2: Confirm each tree is a Gradle library**

```bash
test -f vendor/bitcoin-headers.kmp/settings.gradle.kts
test -f vendor/bip324.kmp/settings.gradle.kts
test -f vendor/bip158.kmp/settings.gradle.kts
test -f vendor/bip157.kmp/settings.gradle.kts
test -f vendor/bitcoin-headers.kmp/library/build.gradle.kts
test -f vendor/bip324.kmp/library/build.gradle.kts
test -f vendor/bip158.kmp/library/build.gradle.kts
test -f vendor/bip157.kmp/library/build.gradle.kts
```

Expected: all eight commands exit `0`.

- [ ] **Step 3: Confirm `.gitmodules` paths and URLs**

`.gitmodules` must contain exactly these four entries (order may vary):

```ini
[submodule "vendor/bitcoin-headers.kmp"]
	path = vendor/bitcoin-headers.kmp
	url = https://github.com/GladosBlueWallet/bitcoin-headers.kmp.git
[submodule "vendor/bip324.kmp"]
	path = vendor/bip324.kmp
	url = https://github.com/GladosBlueWallet/bip324.kmp.git
[submodule "vendor/bip158.kmp"]
	path = vendor/bip158.kmp
	url = https://github.com/GladosBlueWallet/bip158.kmp.git
[submodule "vendor/bip157.kmp"]
	path = vendor/bip157.kmp
	url = https://github.com/GladosBlueWallet/bip157.kmp.git
```

Do not add `vendor/` to `.gitignore`.

- [ ] **Step 4: Commit**

```bash
git add .gitmodules vendor/bitcoin-headers.kmp vendor/bip324.kmp vendor/bip158.kmp vendor/bip157.kmp
git commit -m "$(cat <<'EOF'
Add the four KMP library git submodules under vendor/.

EOF
)"
```

---

### Task 2: Resolve the four libraries in `:shared`

**Files:**
- Create: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Modify: `README.md`
- Modify (only if Gradle configuration fails on AGP): `gradle/libs.versions.toml` `agp` version

**Interfaces:**
- Consumes: Task 1 vendor trees (`vendor/<name>/settings.gradle.kts`, project `:library`)
- Produces: catalog aliases `libs.bitcoin.headers`, `libs.bip324`, `libs.bip157`, `libs.bip158`; `includeVendorBuild` substitutions; `VendorLibrariesTest.libraries_resolve_on_commonTest()`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt`:

```kotlin
package io.bluewallet.blueberry

import bip158.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bitcoin.bip324.Networks
import org.bitcoin.headers.MAINNET_HEADER_CONSENSUS
import org.bitcoin.kmp.bip157.NODE_COMPACT_FILTERS

class VendorLibrariesTest {

    @Test
    fun libraries_resolve_on_commonTest() {
        assertEquals(665_280L, MAINNET_HEADER_CONSENSUS.checkpoint.height)
        assertEquals(8333, Networks.mainnet.defaultPort)
        assertEquals(64, NODE_COMPACT_FILTERS)
        assertEquals(1, hexToBytes("00").size)
    }
}
```

Do not change `SharedCommonTest.kt`.

- [ ] **Step 2: Run the test and confirm it fails to compile**

```bash
./gradlew :shared:jvmTest --tests io.bluewallet.blueberry.VendorLibrariesTest
```

Expected: FAIL. Compiler reports unresolved references (`MAINNET_HEADER_CONSENSUS`, `Networks`, `NODE_COMPACT_FILTERS`, and/or `hexToBytes`). Do not add dependencies yet.

- [ ] **Step 3: Add catalog entries**

In `gradle/libs.versions.toml`, add these version keys under `[versions]` (keep existing keys):

```toml
bitcoin-headers = "0.0.1"
bip324 = "0.0.1"
bip157 = "0.0.1"
bip158 = "0.0.1"
```

Add these library entries under `[libraries]` (keep existing entries):

```toml
bitcoin-headers = { module = "org.bitcoin.kmp:bitcoin-headers", version.ref = "bitcoin-headers" }
bip324 = { module = "org.bitcoin.kmp:bip324", version.ref = "bip324" }
bip157 = { module = "org.bitcoin.kmp:bip157", version.ref = "bip157" }
bip158 = { module = "bip158:bip158", version.ref = "bip158" }
```

- [ ] **Step 4: Include vendor builds and fail if a submodule is missing**

Replace `settings.gradle.kts` with:

```kotlin
rootProject.name = "Blueberry"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

fun includeVendorBuild(dirName: String, module: String) {
    val dir = file("vendor/$dirName")
    require(dir.resolve("settings.gradle.kts").isFile) {
        "Missing vendor/$dirName. Run: git submodule update --init"
    }
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module(module)).using(project(":library"))
        }
    }
}

includeVendorBuild("bitcoin-headers.kmp", "org.bitcoin.kmp:bitcoin-headers")
includeVendorBuild("bip324.kmp", "org.bitcoin.kmp:bip324")
includeVendorBuild("bip157.kmp", "org.bitcoin.kmp:bip157")
includeVendorBuild("bip158.kmp", "bip158:bip158")

include(":androidApp")
include(":desktopApp")
include(":shared")
```

- [ ] **Step 5: Depend on the four libraries from `:shared` `commonMain`**

In `shared/build.gradle.kts`, add these four lines inside `commonMain.dependencies` (keep the existing Compose / lifecycle lines):

```kotlin
            implementation(libs.bitcoin.headers)
            implementation(libs.bip324)
            implementation(libs.bip157)
            implementation(libs.bip158)
```

The block must look like this:

```kotlin
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.bitcoin.headers)
            implementation(libs.bip324)
            implementation(libs.bip157)
            implementation(libs.bip158)
        }
```

Do not add these libraries to `androidApp/build.gradle.kts` or `desktopApp/build.gradle.kts`.

- [ ] **Step 6: Run JVM tests**

```bash
./gradlew :shared:jvmTest
```

Expected: BUILD SUCCESSFUL. `VendorLibrariesTest` and `SharedCommonTest` pass.

If configuration fails because the app AGP is `9.0.1` and a vendor library uses AGP `9.1.0`, change only this line in `gradle/libs.versions.toml`:

```toml
agp = "9.1.0"
```

Do not edit files under `vendor/`. Re-run `./gradlew :shared:jvmTest`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run Android host tests**

```bash
./gradlew :shared:testAndroidHostTest
```

Expected: BUILD SUCCESSFUL. `VendorLibrariesTest` passes on the Android host test task.

- [ ] **Step 8: Confirm Gradle does not fetch the four modules from Maven**

```bash
./gradlew :shared:dependencies --configuration jvmCompileClasspath
```

Expected: `org.bitcoin.kmp:bitcoin-headers`, `org.bitcoin.kmp:bip324`, `org.bitcoin.kmp:bip157`, and `bip158:bip158` resolve as composite / project substitutions (included build `:library`). They must not download from `repo.maven.apache.org` / Maven Central.

- [ ] **Step 9: Document submodule init in README**

Insert the following block in `README.md` immediately before `### Running the apps`. Keep the rest of the README unchanged.

````markdown
### Clone

This repo uses git submodules for the Bitcoin KMP libraries. After clone:

```bash
git submodule update --init
```
````

- [ ] **Step 10: Commit**

```bash
git add \
  shared/src/commonTest/kotlin/io/bluewallet/blueberry/VendorLibrariesTest.kt \
  settings.gradle.kts \
  gradle/libs.versions.toml \
  shared/build.gradle.kts \
  README.md
git commit -m "$(cat <<'EOF'
Resolve the four KMP Bitcoin libraries from vendor submodules.

EOF
)"
```
