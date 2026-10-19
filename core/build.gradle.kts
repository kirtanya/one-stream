plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // HTTP for addons/plugins, HTML parsing for plugins, and the JS engine plugins run in.
    // quickjs-kt is multiplatform: the JVM build (with macOS natives) here, the Android one in :app.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    // Phone → TV sync server (the stream server uses it in :app too).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
