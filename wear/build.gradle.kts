plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
apply(from = rootDir.resolve("signing.gradle.kts"))

android {
    namespace = "com.b12.hatirlat"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.b12.hatirlat"   // telefon ve saatte aynı olmalı
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.2"
    }
    signingConfigs {
        create("b12") {
            storeFile = extra["b12Store"] as File
            storePassword = extra["b12Pass"] as String
            keyAlias = extra["b12Alias"] as String
            keyPassword = extra["b12Pass"] as String
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("b12") }
        getByName("release") { signingConfig = signingConfigs.getByName("b12"); isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
