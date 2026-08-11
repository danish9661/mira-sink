plugins {
    id("com.android.application")
}

android {
    namespace = "com.mira.sink"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mira.sink"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}