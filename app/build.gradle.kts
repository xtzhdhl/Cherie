plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cherieSigningPassword = providers.environmentVariable("CHERIE_SIGNING_PASSWORD").orNull
val cherieSigningStoreFile = rootProject.file("cherie-release.p12")
val hasCherieStableSigning = cherieSigningStoreFile.exists() && !cherieSigningPassword.isNullOrBlank()

android {
    namespace = "com.cherie.read"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cherie.read"
        minSdk = 24
        targetSdk = 34
        versionCode = 142
        versionName = "1.4.2"
    }

    signingConfigs {
        create("cherieStable") {
            storeFile = cherieSigningStoreFile
            storePassword = cherieSigningPassword ?: ""
            keyAlias = "cherie-release"
            keyPassword = cherieSigningPassword ?: ""
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            if (hasCherieStableSigning) {
                signingConfig = signingConfigs.getByName("cherieStable")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasCherieStableSigning) {
                signingConfig = signingConfigs.getByName("cherieStable")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.mozilla:rhino:1.7.15")
}