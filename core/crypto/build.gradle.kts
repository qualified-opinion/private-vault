plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qualifiedopinion.privatevault.crypto"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // argon2kt (JNI) and Tink need keep rules in minified apps; see the file.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // KeysetHandle and StreamingAead are part of this module's public API.
    api(libs.tink.android)
    implementation(libs.argon2kt) {
        // argon2kt only uses androidx.annotation.Keep (already on the classpath via Tink).
        exclude(group = "androidx.appcompat", module = "appcompat")
        exclude(group = "androidx.core", module = "core-ktx")
    }

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.bcprov)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Large streaming test (planning pack §6 PR2): 256 MiB on every run, 3 GiB with -PlargeTests.
val largeTests = providers.gradleProperty("largeTests").isPresent
val largeTestBytes = if (largeTests) 3L * 1024 * 1024 * 1024 else 256L * 1024 * 1024

tasks.withType<Test>().configureEach {
    // Streaming must work in a small heap; LargeStreamingTest asserts peak heap < 48 MiB.
    maxHeapSize = "64m"
    inputs.property("largeTestBytes", largeTestBytes)
    systemProperty("pv.largeTestBytes", largeTestBytes.toString())
    // Big ciphertext fixtures go to Gradle's per-task temp dir (build/tmp/<task>) and are deleted by the test.
    systemProperty("pv.tmpDir", temporaryDir.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
