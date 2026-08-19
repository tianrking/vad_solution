plugins {
    id("com.android.application")
}

android {
    namespace = "com.vadcut.sample.java"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vadcut.sample.java"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":vadcut"))
}
