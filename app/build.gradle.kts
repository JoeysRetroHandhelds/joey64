import java.security.MessageDigest
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
// ScreenScraper's developer login (Joey's account; box art, BoxArt.kt), shipped as ES-DE ships its own: from
// local.properties (gitignored) `screenscraper.devId` / `screenscraper.devPassword`, XOR-scrambled into BuildConfig so the
// APK holds no plain copy. Without them the build looks up no box art.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun ssScramble(s: String): String {
    val key = "Joey64-box-art-key" // as in ScreenScraper.kt
    return s.toByteArray().mapIndexed { i, b -> "%02x".format((b.toInt() xor key[i % key.length].code) and 0xff) }.joinToString("")
}
val copyCoreVersion by tasks.registering(Copy::class) {
    from(rootProject.file("core-version.txt"))
    // The bundled drivers' names for the stats overlay (BundledDrivers.kt; code audit 3, p7 L2).
    from(rootProject.file("devtools/drivers/bundled.json")) { rename { "bundled-drivers.json" } }
    into(buildInfoAssets)
}

// Release check (code audit 3, p7 L1): the core and the bundled drivers aren't committed (*.so), so a release build from
// a clean checkout packed an APK that runs no game, and a hand-copied core named the wrong commit in About, the reports
// and the source offer. Fails on a missing file or one whose md5 isn't the one core-version.txt or
// devtools/drivers/bundled.json records; librashader and libc++_shared only have to be there. Runs before preReleaseBuild.
val checkBundledFiles by tasks.registering {
    val jni = file("src/main/jniLibs/arm64-v8a")
    val drivers = file("src/main/assets/drivers")
    val coreVersion = rootProject.file("core-version.txt")
    val bundledJson = rootProject.file("devtools/drivers/bundled.json")
    doLast {
        fun md5(f: File): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        val problems = mutableListOf<String>()
        fun expect(f: File, want: String?, from: String) {
            if (!f.isFile) { problems += "missing: $f"; return }
            if (want == null) { problems += "$from has no md5 for ${f.name}"; return }
            val got = md5(f)
            if (got != want.lowercase()) problems += "$f: md5 $got, $from says $want"
        }
        val core = File(jni, "libjoey64_libretro_android.so")
        expect(core, Regex("(?m)^md5: *([0-9a-fA-F]{32})").find(coreVersion.readText())?.groupValues?.get(1), "core-version.txt")
        // Netplay needs the core's netplay branch: the app offers rooms with any core, and a core without it ignores
        // N64_NETPLAY, so no game ever reaches the room (rel1 in 0.1.0.1, Thor and a friend's handheld, 2026-11-11). The
        // lobby's one-time keys need N64_NETPLAY_KEY too (core 60065cb): without it every joiner is turned away.
        if (core.isFile) {
            val text = String(core.readBytes(), Charsets.ISO_8859_1)
            for (env in listOf("N64_NETPLAY", "N64_NETPLAY_KEY"))
                if (!Regex("\\b$env\\b").containsMatchIn(text)) problems += "$core: the core doesn't read $env (no netplay: bundle a core with the netplay branch)"
        }
        @Suppress("UNCHECKED_CAST")
        val list = (groovy.json.JsonSlurper().parse(bundledJson) as Map<String, Any?>)["drivers"] as List<Map<String, Any?>>
        for (d in list) expect(File(drivers, d["file"] as String), d["md5"] as String?, "bundled.json")
        for (name in listOf("liblibrashader_capi.so", "libc++_shared.so"))
            if (!File(jni, name).isFile) problems += "missing: ${File(jni, name)}"
        if (problems.isNotEmpty()) throw GradleException("Release check:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("Release check: the core and ${list.size} drivers are the recorded ones")
    }
}

android {
    namespace = "com.joeysretro.joey64"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.joeysretro.joey64"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        buildConfigField("String", "SS_DEV_ID", "\"${ssScramble(localProperties.getProperty("screenscraper.devId", ""))}\"")
        buildConfigField("String", "SS_DEV_PASSWORD", "\"${ssScramble(localProperties.getProperty("screenscraper.devPassword", ""))}\"")
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
    // The real org.json for JVM tests (Android's is a stub there): the updater's release parsing (ReleasesTest).
    testImplementation("org.json:json:20240303")
}

dependencies {
    // .7z ROMs (RomFiles): Apache Commons Compress (Apache-2.0; pulls commons-io and commons-lang3, Apache-2.0) and
    // XZ for Java (public domain, 0BSD) for its LZMA/LZMA2 decoders. Joey's go 2026-11-06.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}

tasks.named("preBuild") { dependsOn(copyCoreVersion) }
afterEvaluate { tasks.named("preReleaseBuild") { dependsOn(checkBundledFiles) } }
