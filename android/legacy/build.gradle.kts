plugins {
    id("com.android.application") version "8.7.3"
}

android {
    namespace = "com.podcastapp.legacy"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.podcastapp.legacy"
        minSdk = 14 // Android 4.0, not just 4.0.3 (API 15).
        targetSdk = 34 // Separate side-load preview; does not change the modern app target.
        versionCode = 1
        versionName = "0.1.0-preview"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true // Android 4.x only understands v1 (JAR).
            enableV2Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = true
    }
}
