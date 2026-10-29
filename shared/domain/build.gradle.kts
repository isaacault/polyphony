// Kotlin Multiplatform: the models and rules every client shares (ARCHITECTURE.md). The JVM target
// serves Android (and later the desktop app); the Apple targets build a framework for the SwiftUI apps,
// and compile only on a Mac (on Linux Gradle skips them). commonMain may use only common Kotlin APIs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework { baseName = "PolyphonyDomain" }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.collections.immutable)
            // PagingData is the contract for lists too large to hand over whole (Songs tab).
            api(libs.androidx.paging.common)
        }
        // JVM only for now: the tests' names have spaces, which Kotlin/Native doesn't allow.
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
