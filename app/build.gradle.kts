import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Apply google-services plugin only when google-services.json exists
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// EP-02A: reproducible dependency resolution evidence.
// Locks the release/debug runtime+compile classpaths so an artifact can be
// tied to an exact resolved dependency set.
dependencyLocking {
    lockAllConfigurations()
}

// Release signing. Credentials are read from, in order of precedence:
//   1. keystore.properties at the repo root (gitignored)
//   2. Gradle properties WOOW_RELEASE_* (e.g. in the user-level gradle.properties)
//   3. Environment variables of the same names (for CI)
// When no credentials are present the release signing config is simply not
// created and `bundleRelease` produces an unsigned artifact, exactly as before.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    keystoreProps.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: System.getenv(name)

val releaseStoreFile = signingValue("WOOW_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("WOOW_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("WOOW_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("WOOW_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() } && file(releaseStoreFile!!).exists()

android {
    namespace = "io.woowtech.odoo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.woowtech.odoo"
        minSdk = 29
        targetSdk = 36
        versionCode = 23
        versionName = "1.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Robolectric + Compose render tests need the merged Android resources/manifest on the
        // JVM unit-test classpath (e.g. PinDotsRow shake render test).
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.biometric)

    // Image Loading
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging
    implementation(libs.timber)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Testing — JUnit 5 + MockK
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    // Robolectric + Compose render tests (JVM). The vintage engine lets these JUnit4/Robolectric
    // tests run under the project's useJUnitPlatform() runner alongside the JUnit 5 suite.
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
