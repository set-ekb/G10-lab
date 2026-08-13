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
        versionCode = 10
        versionName = "0.5.0-alpha"
    }

    signingConfigs {
        create("g10Debug") {
            storeFile = file("g10-debug.keystore")
            storePassword = "g10companion"
            keyAlias = "g10debug"
            keyPassword = "g10companion"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("g10Debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
