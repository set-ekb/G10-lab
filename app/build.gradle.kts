plugins {
    id("com.android.application")
}

android {
    namespace = "com.g10blelab.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.g10blelab.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.3"
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
}
