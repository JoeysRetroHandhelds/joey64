// The Kotlin 2.2 plugin asks for error_prone_annotations 2.27.0, which isn't in the local Gradle
// cache; 2.28.0 is (annotations only, nothing runs). Lets the build resolve offline.
buildscript {
    configurations.classpath {
        resolutionStrategy.force("com.google.errorprone:error_prone_annotations:2.28.0")
    }
}
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
