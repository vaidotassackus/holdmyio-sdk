// Hold My IO Android SDK. No dependencies: Android APIs only (HttpURLConnection, org.json).
plugins {
    id("com.android.library")
    `maven-publish`
}

// One version for the AAR, JitPack and the sdk_version the SDK sends: Core.SDK_VERSION.
val sdkVersion: String = Regex("""const val SDK_VERSION = "([^"]+)"""")
    .find(file("src/main/java/com/holdmyio/Core.kt").readText())!!.groupValues[1]

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// JitPack (jitpack.yml in the public SDK repo) publishes this to Maven:
// implementation("com.github.vaidotassackus:holdmyio-sdk:<version>")
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.vaidotassackus"
            artifactId = "holdmyio-sdk"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only stubs in JVM tests, so the real implementation is added for tests only.
    testImplementation("org.json:json:20240303")
}
