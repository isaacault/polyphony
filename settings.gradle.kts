pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Polyphony"

// Clients share one codebase (ARCHITECTURE.md): shared/ is the logic every client needs (becoming
// Kotlin Multiplatform), android/ the Android app and the modules still tied to Android.
include(":shared:domain")
include(":shared:network")
include(":shared:data")
include(":shared:requests")
include(":shared:providers:subsonic")
include(":shared:providers:jellyfin")
include(":android:app")
include(":android:ui")
include(":android:playback")
include(":android:downloads")
include(":android:dlna")
