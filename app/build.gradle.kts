plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Renders screens on the Mac (phone + TV sizes) so layouts can be checked without a device.
    id("io.github.takahirom.roborazzi")
}

// TMDB read token lives in a local file that is never committed.
val tmdbToken: String = rootProject.file("tmdb.key").takeIf { it.isFile }?.readText()?.trim().orEmpty()

android {
    namespace = "com.kirtanya.reel"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kirtanya.reel"
        minSdk = 28
        targetSdk = 36
        versionCode = 47
        versionName = "0.9.5"
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
    }

    // One APK per ABI: arm64 for the phone, 32-bit arm for the TV.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload: sign release builds with the debug key for now.
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        jniLibs.useLegacyPackaging = true
        // jcifs brings BouncyCastle: its multi-release class copies and ~1.3 MB of data files are never used (docs/HOME_DISK.md §4.2).
        resources.excludes += listOf("META-INF/versions/**", "org/bouncycastle/**/*.properties", "org/bouncycastle/**/*.bin*")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric downloads its Android runtime itself: give it the same trust store as Gradle
                // (the company network inspects HTTPS). Screen renders also fetch TMDB posters.
                listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword").forEach { key ->
                    System.getProperty(key)?.let { value -> test.systemProperty(key, value) }
                }
                test.maxHeapSize = "3g"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    val libtorrent = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Home disk: SMB client for the USB disk on the router (docs/HOME_DISK.md §4; R8 keep rules in proguard-rules.pro).
    implementation("org.codelibs:jcifs:3.0.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Material 3 colour schemes from one seed colour (Google's HCT / tonal-palette algorithm).
    implementation("com.materialkolor:material-kolor:5.0.1")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // Screen renders: ./gradlew :app:recordRoborazziDebug → app/build/screens/*.png
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
