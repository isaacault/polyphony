import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The Last.fm API account is the app's, never committed: `lastfm.apiKey` and `lastfm.apiSecret` in
// local.properties (git-ignored), or POLYPHONY_LASTFM_API_KEY / POLYPHONY_LASTFM_API_SECRET in the
// environment. Without them the build works and Last.fm is simply not offered (ListenBrainz needs no
// app key).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun secretValue(key: String, env: String): String =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() } ?: ""
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
    .getProperty("VERSION_NAME")

android {
    namespace = "app.polyphony.core.scrobble"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "LASTFM_API_KEY", quoted(secretValue("lastfm.apiKey", "POLYPHONY_LASTFM_API_KEY")))
        buildConfigField("String", "LASTFM_API_SECRET", quoted(secretValue("lastfm.apiSecret", "POLYPHONY_LASTFM_API_SECRET")))
        buildConfigField("String", "APP_VERSION", quoted(appVersion))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.generateKotlin", "true")
    // One JSON per schema version; commit them with every version bump.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Scrobbling straight to ListenBrainz and Last.fm (#39): the two clients, the offline queue (its own
// small Room database) and the WorkManager job that drains it.
dependencies {
    api(project(":shared:domain"))
    implementation(project(":shared:network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
