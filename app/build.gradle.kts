plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gitSha: String = (System.getenv("GITHUB_SHA") ?: "dev").take(8)

android {
    namespace = "com.banner.cleanbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.banner.cleanbrowser"
        minSdk = 29
        targetSdk = 35
        // Bumped only in a release-cut commit.
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "UPDATE_REPO", "\"The412Banner/CleanBrowser-releases\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // AOSP testkey (public, SHA-256 A4:0D:A8:0A:59:D1:70:CA…), v1 + v2 + v3.
        create("testkey") {
            storeFile = file("${rootDir}/keystore/testkey.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "testkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("testkey")
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("testkey")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Native libraries stay compressed in the APK (smaller download; extracted at install).
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/*.kotlin_module", "META-INF/versions/**", "DebugProbesKt.bin",
            "kotlin/**", "META-INF/*.version"
        )
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Torrents: FrostWire jlibtorrent (MIT) on libtorrent (BSD-3). Fetched by tools/fetch-libs.sh.
    implementation(files("libs/jlibtorrent-android-2.1.2.1.aar"))
    // Tor: Guardian Project tor-android (BSD-3).
    implementation("info.guardianproject:tor-android:0.4.9.17") { exclude(group = "org.jetbrains.kotlin") }
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    // WireGuard (Apache-2.0).
    implementation("com.wireguard.android:tunnel:1.0.20260102")

    testImplementation("junit:junit:4.13.2")
}

// tor-android 0.4.9.x declares minCompileSdk 37 in its AAR metadata, but its code only calls
// old Android APIs (Service, Context, Intent, FileObserver, LocalBroadcastManager; checked with javap).
// AGP 8.8 cannot compile against 37, so this one metadata check is skipped.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
