# Joey64 release shrinking (R8). The libraries ship their own keep rules; these cover the rest.

# Line numbers in crash logs, mapped back with the build's mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app's own classes keep their names, so stack traces in the session logs stay readable
# without the mapping file (unused code is still removed). This also keeps NativeBridge, whose
# external funs the host library binds by name (Java_com_joeysretro_joey64_NativeBridge_*).
-keepnames class com.joeysretro.joey64.** { *; }

# Kotlin functions that only native code calls (looked up by name with GetStaticMethodID): R8 sees no caller and
# removes them, so the release build crashed at start (2026-11-10). Any new env->Get*MethodID / Get*FieldID on our
# own classes needs a line here.
-keepclassmembers class com.joeysretro.joey64.achievements.RaBridge {
    public static void serverCall(java.lang.String, java.lang.String, long);
    public static void onEvent(java.lang.String);
}

# Commons Compress refers to optional codecs (brotli, zstd, asm) that aren't in the build.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
