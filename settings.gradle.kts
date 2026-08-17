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

fun includeVendorBuild(dirName: String, module: String, projectPath: String) {
    val dir = file("vendor/$dirName")
    require(dir.resolve("settings.gradle.kts").isFile) {
        "Missing vendor/$dirName. Run: git submodule update --init"
    }
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module(module)).using(project(projectPath))
        }
    }
}

includeVendorBuild("bitcoin-headers.kmp", "io.bluewallet:bitcoin-headers", ":bitcoin-headers")
includeVendorBuild("bip324.kmp", "io.bluewallet:bip324", ":bip324")
includeVendorBuild("bip157.kmp", "io.bluewallet:bip157", ":bip157")
includeVendorBuild("bip158.kmp", "io.bluewallet:bip158", ":bip158")

include(":androidApp")
include(":desktopApp")
include(":shared")
