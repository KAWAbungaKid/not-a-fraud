plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val dietVersionName = project.property("dietVersionName") as String
val dietVersionCode = project.property("dietVersionCode") as String

android {
    namespace = "com.maven.diet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.maven.diet"
        minSdk = 34
        targetSdk = 36
        versionCode = dietVersionCode.toInt()
        versionName = dietVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Same key as Not a Fraud: lets the two apps share data through a signature-level permission.
    signingConfigs {
        create("naf") {
            storeFile = rootProject.file("keystore/notafraud.jks")
            storePassword = "notafraud-sideload"
            keyAlias = "notafraud"
            keyPassword = "notafraud-sideload"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Twig dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("naf")
        }
        release {
            manifestPlaceholders["appLabel"] = "Not a Twig"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("naf")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":feedback"))
    implementation(project(":diet-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.health.connect)
    // On-device text recognition for nutrition labels (bundled Latin model: works offline, photo never leaves the phone).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
