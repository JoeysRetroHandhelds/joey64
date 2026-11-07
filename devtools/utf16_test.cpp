// Checks app/src/main/cpp/utf16.h (achievements' Java strings). Build for arm64 and run on a device:
//   <ndk>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang++ -static-libstdc++ -O1 \
//     -I app/src/main/cpp devtools/utf16_test.cpp -o utf16_test
//   adb push utf16_test /data/local/tmp/ && adb shell /data/local/tmp/utf16_test
#include "utf16.h"
#include <cstdio>

static int fails = 0;
static void check(const char* name, const char* in, std::u16string want) {
    std::u16string got = utf8To16(in);
    if (got == want) { std::printf("ok   %s\n", name); return; }
    ++fails;
    std::printf("FAIL %s: got", name);
    for (char16_t c : got) std::printf(" %04x", (unsigned)c);
    std::printf("\n");
}

int main() {
    check("ascii", "Bottle Run", u"Bottle Run");
    check("2-byte", "Pok\xc3\xa9mon", u"Pokémon");
    check("3-byte", "\xe2\x98\x85 Star", u"★ Star");
    check("emoji (4-byte)", "Gold \xf0\x9f\x8f\x86", u"Gold \U0001F3C6");
    check("null", nullptr, u"");
    check("stray continuation", "a\x80" "b", u"a�b");
    check("cut short at the end", "a\xf0\x9f", u"a�");
    check("cut short mid-string", "\xe2\x98" "x", u"�x");
    check("bad lead byte", "\xff" "z", u"�z");
    check("encoded surrogate", "\xed\xa0\x80", u"�");
    check("past U+10FFFF", "\xf4\x90\x80\x80", u"�");
    std::printf("%s\n", fails ? "FAILED" : "all passed");
    return fails ? 1 : 0;
}
