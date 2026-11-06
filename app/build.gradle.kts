import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build stamp for the session logs (LogFiles.kt): which app commit a tester's APK was built from, and whether the tree
// had uncommitted changes. core-version.txt (devtools/bundle-core.sh: the bundled core's commit and md5) is packed as an
// asset so the logs can name the core too.
fun git(vararg args: String): String = try {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
} catch (e: Exception) { "" }
val gitCommit = git("rev-parse", "--short=10", "HEAD").ifEmpty { "unknown" }
val gitDirty = git("status", "--porcelain", "--untracked-files=no").isNotEmpty()
val buildInfoAssets = layout.buildDirectory.dir("generated/buildinfo")

// Release signing, as JoeyOS does it: keystore.properties (gitignored; keystore.properties.example shows the keys) names
// the Joey64 key, which lives outside the repo. Without the file a release build comes out unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val copyCoreVersion by tasks.registering(Copy::class) {
    from(rootProject.file("core-version.txt"))
    into(buildInfoAssets)
}

android {
    namespace = "com.joeysretro.joey64"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.joeysretro.joey64"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 removes unused code and resources: debug 39.7 MB -> 10.1 MB, measured 2026-11-06.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.containsKey("storeFile")) signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets["main"].assets.srcDir(buildInfoAssets)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = true } } // core .so must be extracted for dlopen
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Launcher UI (docs/launcher-build-plan.md, Decision 1: Compose and Material 3, like JoeyOS).
// Versions are the ones already in the local Gradle cache; JoeyOS's BOM 2026.06.01 is newer.
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}

dependencies {
    // .7z ROMs (RomFiles): Apache Commons Compress (Apache-2.0; pulls commons-io and commons-lang3, Apache-2.0) and
    // XZ for Java (public domain, 0BSD) for its LZMA/LZMA2 decoders. Joey's go 2026-11-06.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}

tasks.named("preBuild") { dependsOn(copyCoreVersion) }
