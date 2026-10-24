plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qualifiedopinion.privatevault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qualifiedopinion.privatevault"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumented tests run against R8 output (planning pack §6 PR3): argon2kt (JNI) and Tink ship
    // without (complete) shrinker rules, so unlock must be proven on a minified build, not on debug.
    testBuildType = "releaseCheck"

    buildTypes {
        release {
            // Unsigned: no release keystore in the repo. Debug builds use the default debug key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Test-only: the release build (same R8 config) signed with the debug key so the androidTest
        // APK can instrument it. Never distributed; the permission gate checks app-release-unsigned.apk.
        create("releaseCheck") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Keeps only our own entry points the tests call; see the file for why that can't mask
            // a Tink/argon2kt shrinking problem.
            proguardFile("proguard-releasecheck.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:vault"))
    implementation(project(":core:media"))
    implementation(project(":feature:import"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
