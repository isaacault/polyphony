plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.polyphony.core.playback"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.media3.common.util.UnstableApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

/** Robolectric's Android jar, for offline test runs (below). */
val robolectricSdk: Configuration by configurations.creating { isTransitive = false }

dependencies {
    api(project(":shared:domain"))
    implementation(project(":shared:network"))
    implementation(project(":android:dlna"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.process)

    api(libs.androidx.media3.common)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.cast)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    api(libs.play.services.cast.framework)
    api(libs.androidx.mediarouter)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // The adaptive streams' tests run Media3's real HLS player under Robolectric (HlsPlaybackTest).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    robolectricSdk(libs.robolectric.android.all)
}

// Robolectric's Android comes through Gradle (so the CI's Gradle volume caches it) and Robolectric
// runs offline: the CI container keeps nothing else between runs.
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric"))
}
val robolectricDir: String = layout.buildDirectory.dir("robolectric").get().asFile.absolutePath
tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir)
    maxHeapSize = "1g"
}
