import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val appVersionCode = project.property("appVersionCode") as String

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "com.maven.notafraud.desktop.MainKt"
        jvmArgs += listOf("-Xmx256m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "NotAFraud"
            // MSI needs MAJOR >= 1, so the desktop build is 1.0.<versionCode>.
            packageVersion = "1.0.$appVersionCode"
            description = "Not a Fraud: what you should be doing, on the PC"
            vendor = "Maven"
            modules("java.naming", "jdk.unsupported")
            windows {
                menuGroup = "Not a Fraud"
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "6f3b8c1e-2a47-4d1b-9e55-6a2f0c9d7b31"
            }
        }
    }
}
