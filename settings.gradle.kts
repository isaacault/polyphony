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

include(":app")
include(":core:domain")
include(":core:network")
include(":core:data")
include(":core:playback")
include(":core:ui")
include(":core:downloads")
include(":core:dlna")
include(":provider:subsonic")
include(":provider:jellyfin")
