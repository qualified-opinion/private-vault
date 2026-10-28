plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qualifiedopinion.privatevault.importer"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ItemRepository, VaultSession and StateFlow are part of this module's API.
    api(project(":core:vault"))

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Same small heap as :core:vault: import streams, it never buffers a whole file.
    maxHeapSize = "64m"
    systemProperty("pv.tmpDir", temporaryDir.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
