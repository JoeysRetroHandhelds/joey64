plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
val copyCoreVersion by tasks.registering(Copy::class) {
    from(rootProject.file("core-version.txt"))
    into(buildInfoAssets)
}

android {
    namespace = "com.joeysretro.joey64"
    compileSdk = 34
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
    sourceSets["main"].assets.srcDir(buildInfoAssets)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = true } } // core .so must be extracted for dlopen
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // .7z ROMs (RomFiles): Apache Commons Compress (Apache-2.0; pulls commons-io and commons-lang3, Apache-2.0) and
    // XZ for Java (public domain, 0BSD) for its LZMA/LZMA2 decoders. Joey's go 2026-11-06.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}

tasks.named("preBuild") { dependsOn(copyCoreVersion) }
