plugins {
    id("com.android.library")
}

android {
    namespace = "com.tianrking.vadsolution.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
}
