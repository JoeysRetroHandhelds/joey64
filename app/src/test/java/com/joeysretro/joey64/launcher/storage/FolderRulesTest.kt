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

    // Where the picker opens

    @Test fun gamesOnTheCardBeatAnEmptyInternalRoms() {
        // The Thor: internal ROMs empty, the SD card's holds n64 with 82 games.
        val looks = listOf(RomsLook("primary", 0, null), RomsLook("6439-6232", 42, 82))
        assertEquals("6439-6232", RomFolderRules.startVolume(looks))
    }

    @Test fun anEmptyN64FolderLosesToOneWithGames() {
        // ES-DE makes every system's folder, so an empty internal n64 is common.
        val looks = listOf(RomsLook("primary", 40, 0), RomsLook("6439-6232", 42, 82))
        assertEquals("6439-6232", RomFolderRules.startVolume(looks))
    }

    @Test fun internalStorageFirstWhenBothHaveGames() {
        val looks = listOf(RomsLook("primary", 40, 12), RomsLook("6439-6232", 42, 82))
        assertEquals("primary", RomFolderRules.startVolume(looks))
    }

    @Test fun anN64FolderCountsWhenItsFilesAreHidden() {
        // Where Android hides other apps' file names, every N64 folder reads empty.
        val looks = listOf(RomsLook("primary", 3, null), RomsLook("6439-6232", 42, 0))
        assertEquals("6439-6232", RomFolderRules.startVolume(looks))
    }

    @Test fun noRomsAnywhereOpensInternalStorage() {
        assertEquals("primary", RomFolderRules.startVolume(listOf(RomsLook("primary", null, null), RomsLook("6439-6232", null, null))))
        assertEquals("primary", RomFolderRules.startVolume(emptyList()))
        // A non-empty ROMs without an N64 folder still beats nothing.
        assertEquals("6439-6232", RomFolderRules.startVolume(listOf(RomsLook("primary", null, null), RomsLook("6439-6232", 5, null))))
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

    @Test fun pickerOpensAtTheN64FolderWhenThereIsOne() {
        val looks = listOf(RomsLook("primary", 0, null), RomsLook("6439-6232", 42, 82, "n64"))
        assertEquals("6439-6232:ROMs/n64", RomFolderRules.startFolder(looks))
        assertEquals("6439-6232:ROMs/Nintendo 64", RomFolderRules.startFolder(listOf(RomsLook("6439-6232", 42, 82, "Nintendo 64"))))
        // No N64 folder: the ROMs folder.
        assertEquals("primary:ROMs", RomFolderRules.startFolder(listOf(RomsLook("primary", 3, null))))
        // No ROMs folder anywhere (the Duo): the top of internal storage, never a folder that isn't there.
        assertEquals("primary:", RomFolderRules.startFolder(listOf(RomsLook("primary", null, null))))
        assertEquals("primary:", RomFolderRules.startFolder(emptyList()))
    }
}
