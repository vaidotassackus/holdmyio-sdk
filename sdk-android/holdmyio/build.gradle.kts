// Hold My IO Android SDK. No dependencies: Android APIs only (HttpURLConnection, org.json).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.holdmyio"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only stubs in JVM tests, so the real implementation is added for tests only.
    testImplementation("org.json:json:20240303")
}
