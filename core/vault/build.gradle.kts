plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qualifiedopinion.privatevault.vault"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // VaultSession hands out VaultCrypto/KeysetHandle; StateFlow is part of the public API.
    api(project(":core:crypto"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    // Same small heap as :core:crypto: nothing in the vault layer may buffer a whole file.
    maxHeapSize = "64m"
    systemProperty("pv.tmpDir", temporaryDir.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
