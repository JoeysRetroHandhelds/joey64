package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which cartridge an add-on 64DD disk needs, and the names the missing-cartridge popup gives it. */
class DdCartTest {
    private fun game(title: String, md5: String = "0".repeat(32)) = LibraryGame(1, title, md5)

    @Test fun byMd5() {
        assertEquals("CFZJ", GameLauncher.cartCode(game("anything", "cb2fb00c3921245ae04bb38ba01abe92")))
        assertEquals("CDZJ", GameLauncher.cartCode(game("anything", "40aa8ec52c5e025d6c6ad94cebc2218f")))
    }

    @Test fun byTitleForOtherDumps() {
        assertEquals("CFZJ", GameLauncher.cartCode(game("F-Zero X - Expansion Kit (Japan) [b]")))
        assertEquals("CDZJ", GameLauncher.cartCode(game("Dezaemon DD (Japan) (Proto)")))
    }

    @Test fun otherDisksNeedNone() {
        assertNull(GameLauncher.cartCode(game("SimCity 64 (Japan)")))
    }

    @Test fun everyCodeHasPopupNames() {
        for (code in GameLauncher.DD_CARTS.values + GameLauncher.DD_CART_TITLES.values) {
            assert(GameLauncher.CART_NAMES.containsKey(code)) { code }
            assert(GameLauncher.DISK_NAMES.containsKey(code)) { code }
        }
    }
}
