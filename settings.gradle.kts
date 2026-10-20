pluginManagement {
    repositories {
        google()
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
rootProject.name = "not-a-fraud"
include(":core")
if (file("android/build.gradle.kts").exists()) include(":android")
if (file("desktop/build.gradle.kts").exists()) include(":desktop")
