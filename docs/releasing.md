# Releasing Joey64

## The signing key (once)

Joey64 has its own release key (Joey, 2026-11-06), kept outside the repo like JoeyOS's. Joey makes it
and picks the password; it is never typed by anyone else. In PowerShell:

```
New-Item -ItemType Directory -Force C:\Users\Joey\keystores
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\Users\Joey\keystores\joey64-release.jks -alias joey64 -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Joey64"
```

keytool asks for the password twice. Then copy `keystore.properties.example` to `keystore.properties`
(gitignored) and fill in both passwords (the same one, unless a separate key password was chosen).

**Back the .jks and its password up somewhere safe.** Android only installs an update signed with the
same key, so a lost key means every handheld has to uninstall Joey64 (and lose its data) to move to
a new one.

## A release build

```
./gradlew assembleRelease -Dorg.gradle.jvmargs=-Xmx6g
```

R8 needs the larger heap. Output: `app/build/outputs/apk/release/app-release.apk` (about 10 MB; the
debug build is about 40 MB). Without `keystore.properties` it comes out as `app-release-unsigned.apk`.

Checked 2026-11-06 on the Thor (a copy signed with the debug key): the library starts a zipped game,
60 fps, no JNI or missing-class errors. The keep rules are in `app/proguard-rules.pro`.

## Publishing

- Version: `versionName` in `app/build.gradle.kts`. Public builds have three numbers ("0.2.0"),
  testing builds a fourth ("0.2.0.1"), published as a GitHub pre-release. `versionCode` goes up by one
  every release.
- The in-app updater reads the joey64 repo's GitHub releases, so the repo has to be public first
  (Joey's step; Joey 2026-11-06: not yet).
- A release is made on GitHub only on Joey's go for that build.
