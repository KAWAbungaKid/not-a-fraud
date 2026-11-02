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
// Workout app (separate APK, same repo + signing key so the two apps can share data).
if (file("workout-core/build.gradle.kts").exists()) include(":workout-core")
if (file("workout/build.gradle.kts").exists()) include(":workout")
// Diet app "Not a Twig" (same key, same reason).
if (file("diet-core/build.gradle.kts").exists()) include(":diet-core")
if (file("diet/build.gradle.kts").exists()) include(":diet")
