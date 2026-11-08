package com.joeysretro.joey64.launcher.boxart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** joey64-data's boxart/index.tsv as the app reads it. */
class JoeyCoversTest {
    @Test fun readsRomAndPictureMd5s() {
        val m = JoeyCovers.parse(
            "# Joey64's own covers\n" +
                "193C195A5C5B28ACD0D7346F050DEEA5\tbc779a20d8f87be11e9c8fea54daeeef\tSuper Mario 64 - Elise\n" +
                "\n" +
                "605c74e34d0302707723b191ea30ae44\t5d99bf252b7bb345de8c42556610cbb4\n" +
                "not-an-md5\t5d99bf252b7bb345de8c42556610cbb4\tx\n" +
                "7906333f41184f6cfda9d364a6f8d565\tnot-a-picture-md5\tx\n",
        )
        assertEquals(mapOf(
            "193c195a5c5b28acd0d7346f050deea5" to "bc779a20d8f87be11e9c8fea54daeeef",
            "605c74e34d0302707723b191ea30ae44" to "5d99bf252b7bb345de8c42556610cbb4",
        ), m)
    }

    /** The data repo's own index, when it's checked out next to the launcher: every line reads. */
    @Test fun readsTheDataReposIndex() {
        val f = listOf("../../joey64-data/boxart/index.tsv", "../joey64-data/boxart/index.tsv").map(::File).firstOrNull { it.exists() } ?: return
        val lines = f.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(lines, JoeyCovers.parse(f.readText()).size)
    }
}
