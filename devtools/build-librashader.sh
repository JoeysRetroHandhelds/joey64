#!/bin/sh
# Builds liblibrashader_capi.so (RetroArch .slangp presets, libra.h) for arm64 Android and puts it in jniLibs with
# the NDK's libc++_shared.so beside it (it needs that; the host links libc++ statically). Neither .so is committed.
# Needs: a librashader checkout (default C:/GithubProjects/librashader, v0.12.1), Rust stable with the
# x86_64-pc-windows-gnu host and the aarch64-linux-android target (rustup in ~/.rustup, cargo in ~/.cargo), NDK 27.3.
# Usage: devtools/build-librashader.sh [librashader dir]
set -e
SRC="${1:-C:/GithubProjects/librashader}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-C:/Users/$USERNAME/AppData/Local/Android/Sdk}"
NDK="$SDK/ndk/27.3.13750724"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
# The gnu host's own dlltool lives here; the PATH change is for this script only.
SC="$HOME/.rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained"
export PATH="$HOME/.cargo/bin:$SC:$PATH"
export CC_aarch64_linux_android="$TC/bin/aarch64-linux-android29-clang.cmd"
export CXX_aarch64_linux_android="$TC/bin/aarch64-linux-android29-clang++.cmd"
export AR_aarch64_linux_android="$TC/bin/llvm-ar.exe"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/bin/aarch64-linux-android29-clang.cmd"
export ANDROID_NDK_ROOT="$NDK"
cd "$SRC"
# getrandom on the gnu host needs no assembler only with the windows_legacy backend; host.rustflags reaches the
# build scripts' own crates (target rustflags don't), which needs the unstable host-config.
RUSTC_BOOTSTRAP=1 cargo build -Zhost-config -Ztarget-applies-to-host --config 'target-applies-to-host=false' \
  --config 'host.rustflags=["--cfg","getrandom_backend=\"windows_legacy\""]' \
  --release --locked -p librashader-capi --target aarch64-linux-android \
  --no-default-features --features runtime-vulkan,runtime-opengl
OUT="$HERE/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$TC/bin/llvm-strip.exe" -o "$OUT/liblibrashader_capi.so" target/aarch64-linux-android/release/liblibrashader_capi.so
cp "$TC/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"
cp include/librashader.h "$HERE/app/src/main/cpp/third_party/librashader/include/"
ls -la "$OUT/liblibrashader_capi.so" "$OUT/libc++_shared.so"
echo "librashader $(git describe --tags --always) built. If its crates changed, regenerate assets/licenses/librashader.txt."
