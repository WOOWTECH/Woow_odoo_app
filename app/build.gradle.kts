import java.util.Properties
import com.google.gms.googleservices.GoogleServicesTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Always wire Firebase tasks: a missing client configuration must not produce a push-less APK.
apply(plugin = libs.plugins.google.services.get().pluginId)

// EP-02A: reproducible dependency resolution evidence.
// Locks the release/debug runtime+compile classpaths so an artifact can be
// tied to an exact resolved dependency set.
dependencyLocking {
    lockAllConfigurations()
}

// Brand-specific signing inputs; never share a fallback key. No secret values are logged.
fun signingProperties(brand: String): Properties = Properties().apply {
    val source = rootProject.file(if (brand == "woowtech") "keystore.properties" else "apporo-keystore.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val woowSigning = signingProperties("woowtech")
val apporoSigning = signingProperties("apporo")
fun signingValue(brand: String, suffix: String): String? {
    val name = "${if (brand == "apporo") "APPORO" else "WOOW"}_RELEASE_$suffix"
    return (if (brand == "apporo") apporoSigning else woowSigning).getProperty(name)
        ?: providers.gradleProperty(name).orNull ?: System.getenv(name)
}
fun hasSigning(brand: String): Boolean =
    listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all {
        !signingValue(brand, it).isNullOrBlank()
    } && file(signingValue(brand, "STORE_FILE")!!).exists()

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
        for (brand in listOf("woowtech", "apporo")) {
            if (hasSigning(brand)) {
                create("${brand}Release") {
                    storeFile = file(signingValue(brand, "STORE_FILE")!!)
                    storePassword = signingValue(brand, "STORE_PASSWORD")
                    keyAlias = signingValue(brand, "KEY_ALIAS")
                    keyPassword = signingValue(brand, "KEY_PASSWORD")
                }
            }
        }
    }

    flavorDimensions += "brand"
    productFlavors {
        create("woowtech") {
            dimension = "brand"
            applicationId = "io.woowtech.odoo"
            buildConfigField("String", "APP_BRAND", "\"woowtech\"")
            manifestPlaceholders["brandScheme"] = "woowodoo"
            signingConfig = signingConfigs.findByName("woowtechRelease")
        }
        create("apporo") {
            dimension = "brand"
            applicationId = "com.apporo.odoo"
            versionName = "1.0"
            // vc1 候選（b4cb1e8）因 W1-3／W1-10 程式變更作廢；每次上傳手動遞增。
            // vc2＝aa49043（Play 內部測試 2026-09-27）；vc3＝048218b（Play 內部測試 2026-09-29）；
            // vc4＝pi 0929 五輪複查的帳號切換隔離修正（7ab78a1）；
            // vc5＝F5＋1001 系列帳號／推播隔離修正（pi 1001I 原始碼複查無 P1/P2，cf3dd17）。
            versionCode = 5
            buildConfigField("String", "APP_BRAND", "\"apporo\"")
            manifestPlaceholders["brandScheme"] = "apporoodoo"
            signingConfig = signingConfigs.findByName("apporoRelease")
        }
    }

    buildTypes {
        release {
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

// Explicit per-variant files prevent the Google plugin's normal root-file fallback for Apporo.
androidComponents {
    onVariants(selector().all()) { variant ->
        val brand = variant.productFlavors.single { it.first == "brand" }.second
        val release = variant.buildType == "release"
        if (brand == "apporo" && !release) {
            variant.manifestPlaceholders.put("brandScheme", "apporoodoo-dev")
        }
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val variantConfig = file("src/${variant.name}/google-services.json")
        val firebaseConfig = if (brand == "woowtech" && !variantConfig.exists()) {
            file("google-services.json") // Existing WOOW client only; never copied or changed.
        } else variantConfig
        val validateBrand = tasks.register<Exec>("validate${capitalized}BrandConfig") {
            group = "verification"
            // python3 stdlib only; no network, no key material, no config values printed.
            commandLine("python3", rootProject.file("scripts/validate_brand_config.py"),
                "--brand", brand, "--build-type", variant.buildType!!,
                "--config", firebaseConfig)
            doFirst {
                if (brand == "apporo" && release) {
                    check(hasSigning("apporo")) { "Apporo release requires its own APPORO_RELEASE_* signing inputs" }
                    val apporoStore = file(signingValue("apporo", "STORE_FILE")!!).canonicalFile
                    val woowStore = signingValue("woowtech", "STORE_FILE")?.let { file(it).canonicalFile }
                    check(apporoStore != woowStore &&
                        apporoStore != file("${System.getProperty("user.home")}/keystores/woow-odoo-release.jks").canonicalFile) {
                        "Apporo release must not use the WOOW keystore"
                    }
                }
            }
        }
        tasks.withType<GoogleServicesTask>().configureEach {
            if (name == "process${capitalized}GoogleServices") {
                googleServicesJsonFiles.set(listOf(firebaseConfig))
                dependsOn(validateBrand)
            }
        }
        tasks.matching { it.name == "pre${capitalized}Build" }.configureEach {
            dependsOn(validateBrand)
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
