#pragma once
// UTF-8 to UTF-16 for Java strings (achievements.cpp's jstr). JNI's NewStringUTF takes modified UTF-8 only: a 4-byte
// sequence (an emoji in an achievement title) aborts the app under CheckJNI (debug builds). Here 4-byte sequences
// become surrogate pairs; broken bytes, encoded surrogates and code points past U+10FFFF become U+FFFD.
// Tested on a device by devtools/utf16_test.cpp.
#include <cstdint>
#include <string>

inline std::u16string utf8To16(const char* s) {
    std::u16string out;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s ? s : "");
    while (*p) {
        uint32_t c = *p;
        int extra = c < 0x80 ? 0 : (c >> 5) == 6 ? 1 : (c >> 4) == 14 ? 2 : (c >> 3) == 30 ? 3 : -1;
        if (extra < 0) { out.push_back(0xFFFD); ++p; continue; }
        c &= extra == 0 ? 0x7F : extra == 1 ? 0x1F : extra == 2 ? 0x0F : 0x07;
        int k = 1;
        for (; k <= extra; ++k) { if ((p[k] & 0xC0) != 0x80) break; c = (c << 6) | (p[k] & 0x3F); }
        if (k <= extra) { out.push_back(0xFFFD); p += k; continue; } // cut short (p[k] may be the end)
        p += extra + 1;
        if (c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) out.push_back(0xFFFD);
        else if (c >= 0x10000) { c -= 0x10000; out.push_back(char16_t(0xD800 + (c >> 10))); out.push_back(char16_t(0xDC00 + (c & 0x3FF))); }
        else out.push_back(char16_t(c));
    }
    return out;
}
