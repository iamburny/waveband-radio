import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from keystore.properties in the project root (gitignored) or, failing that,
// environment variables; blank values count as unset. Without a keystore, release APKs are produced
// unsigned and bundleRelease fails, because Play rejects unsigned bundles.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, envVar: String): String? =
    keystoreProperties.getProperty(property)?.takeUnless { it.isBlank() }
        ?: System.getenv(envVar)?.takeUnless { it.isBlank() }

val releaseKeystore = signingValue("storeFile", "WAVEBAND_KEYSTORE")

android {
    namespace = "com.iamburny.waveband"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.iamburny.waveband"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                targets += listOf("dabtuner_jni")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("storePassword", "WAVEBAND_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "WAVEBAND_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "WAVEBAND_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Ships native symbol tables in the bundle so Play Console can symbolicate native crashes.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    // Local copy so the action captures a plain value, which the configuration cache can store.
    val signed = releaseKeystore != null
    doFirst {
        if (!signed) {
            throw GradleException("bundleRelease needs an upload key: set up keystore.properties (see keystore.properties.example).")
        }
    }
}
