plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.glyphrank.dota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glyphrank.dota"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // android.util.Log etc. return defaults in JVM tests instead of throwing "Stub!".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Nothing's Glyph Matrix SDK. Its licence forbids redistribution, so it is not
    // included here: download it into app/libs/ (see app/libs/README.md).
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
