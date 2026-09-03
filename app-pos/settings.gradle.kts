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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MPAndroidChart is published only on JitPack -- it has no Maven Central release.
        // Scoped to that one group so adding the repo does not widen where every other
        // dependency may be resolved from.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.PhilJay") }
        }
    }
}

rootProject.name = "app-pos"
include(":app")
include(":core-domain")
include(":core-data")
include(":core-network")
