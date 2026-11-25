import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Phones are arm64; the emulator's x86_64 copy of the core (2.4 MB) goes in
// only when asked for: `gradlew assembleRelease -Pemulator`.
val abis = if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")

android {
    namespace = "app.booruhub"
    // Android 17 (API 37) is what the Pixel 7 it is made for runs.
    compileSdkVersion("android-37.0")
    // The NDK that builds the Rust core (crates/android) into libbooruhub.so.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.booruhub"
        minSdk = 29
        // arm64 for phones, x86_64 for the emulator.
        ndk { abiFilters += abis }
        targetSdk = 37
        versionCode = 15
        versionName = "0.5.2"
    }

    // Release builds are signed with the key named in android/keystore.properties
    // (storeFile, storePassword, keyAlias, keyPassword), which stays out of git.
    // Without it a build is signed with the SDK's debug key: it runs, but will
    // not install over the official APKs.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keys != null) create("hub") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("hub") ?: signingConfigs.getByName("debug")
        }
        debug { signingConfigs.findByName("hub")?.let { signingConfig = it } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/rust"))
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// The Rust core, built for the phone and the emulator before every build that
// needs it. Gradle skips it when nothing under crates/ changed.
val repo = rootDir.parentFile
val rust = tasks.register<Exec>("buildRust") {
    val out = layout.buildDirectory.dir("generated/rust").get().asFile
    inputs.dir(File(repo, "crates"))
    inputs.files(File(repo, "Cargo.toml"), File(repo, "Cargo.lock"))
    inputs.property("abis", abis)
    outputs.dir(out)
    workingDir = repo
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    // A global rustc-wrapper (kache) cannot wrap the C compiler ring needs.
    environment("RUSTC_WRAPPER", "")
    commandLine(
        listOf("cargo", "ndk") + abis.flatMap { listOf("-t", it) } +
            listOf("-P", "29", "-o", out.absolutePath, "build", "--release", "-p", "booruhub-android"),
    )
}
tasks.named("preBuild") { dependsOn(rust) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val coil = "3.3.0"
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
    implementation("io.coil-kt.coil3:coil-gif:$coil")
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
}
