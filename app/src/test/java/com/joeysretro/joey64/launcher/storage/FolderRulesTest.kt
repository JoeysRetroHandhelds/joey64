package com.joeysretro.joey64.launcher.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRulesTest {

    // ROM folders

    @Test fun esdeLayoutFindsTheN64Folder() {
        assertEquals(listOf("n64"), RomFolderRules.foldersToScan("ROMs", listOf("gba", "n64", "psx")))
    }

    @Test fun everyN64NamedFolderCounts() {
        assertEquals(listOf("N64", "Nintendo 64"), RomFolderRules.foldersToScan("Games", listOf("N64", "SNES", "Nintendo 64")))
    }

    @Test fun pickedTheN64FolderItself() {
        assertEquals(listOf(""), RomFolderRules.foldersToScan("n64", listOf("hacks")))
        assertEquals(listOf(""), RomFolderRules.foldersToScan("Nintendo 64", emptyList()))
    }

    @Test fun noN64FolderScansThePickedFolder() {
        assertEquals(listOf(""), RomFolderRules.foldersToScan("My N64 games", listOf("hacks", "translations")))
    }

    @Test fun romNames() {
        for (n in listOf("Super Mario 64 (USA).z64", "a.N64", "b.v64", "c.zip", "d.7Z")) assertTrue(n, RomFolderRules.isRomName(n))
        for (n in listOf("._Super Mario 64.z64", "readme.txt", "Mario.srm", "z64", "cover.png")) assertFalse(n, RomFolderRules.isRomName(n))
    }

    // Overlapping data folders

    private val ext = "com.android.externalstorage.documents"

    @Test fun sameFolderOverlaps() {
        assertTrue(TreeRules.overlaps(ext, "primary:Joey64", ext, "primary:Joey64"))
    }

    @Test fun folderInsideAnotherOverlaps() {
        assertTrue(TreeRules.overlaps(ext, "primary:Joey64", ext, "primary:Joey64/saves"))
        assertTrue(TreeRules.overlaps(ext, "primary:Joey64/saves", ext, "primary:Joey64"))
        assertTrue(TreeRules.overlaps(ext, "primary:", ext, "primary:Joey64"))
    }

    @Test fun siblingsWithACommonPrefixDontOverlap() {
        assertFalse(TreeRules.overlaps(ext, "primary:Joey64", ext, "primary:Joey64 old"))
    }

    @Test fun otherVolumesAndProvidersDontOverlap() {
        assertFalse(TreeRules.overlaps(ext, "primary:Joey64", ext, "1234-ABCD:Joey64"))
        assertFalse(TreeRules.overlaps(ext, "primary:Joey64", "com.other.provider", "primary:Joey64"))
        assertTrue(TreeRules.overlaps("com.other.provider", "x", "com.other.provider", "x"))
        assertFalse(TreeRules.overlaps("com.other.provider", "x", "com.other.provider", "x/y"))
    }
}
