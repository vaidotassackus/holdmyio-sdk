// Hold My IO Android SDK. Jokių priklausomybių: tik Android API (HttpURLConnection, org.json).
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
    // Android org.json JVM testuose yra tik stub'ai: tikra implementacija tik testams.
    testImplementation("org.json:json:20240303")
}
