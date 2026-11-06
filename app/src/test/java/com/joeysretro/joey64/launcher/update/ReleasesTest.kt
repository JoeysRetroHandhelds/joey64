package com.joeysretro.joey64.launcher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun release(tag: String, asset: String?, pre: Boolean = false, draft: Boolean = false, notes: String = "") =
        """{"tag_name":"v$tag","prerelease":$pre,"draft":$draft,"body":"$notes","assets":[""" +
            (if (asset == null) "" else """{"name":"$asset","size":1234,"browser_download_url":"https://example.test/$asset"}""") + "]}"

    @Test fun comparesNumbersNotText() {
        assertTrue(Releases.compare("0.2.10", "0.2.9") > 0)
        assertTrue(Releases.compare("0.2.0.1", "0.2.0") > 0)
        assertTrue(Releases.compare("0.3", "0.2.99") > 0)
        assertEquals(0, Releases.compare("0.2", "0.2.0"))
        assertTrue(Releases.compare("0.1", "0.1.1") < 0)
    }

    @Test fun aFourthNumberIsATestingBuild() {
        assertTrue(Releases.isTesting("0.3.0.2"))
        assertFalse(Releases.isTesting("0.3.0"))
    }

    @Test fun publicCheckOffersTheLatestWhenNewer() {
        val r = Releases.newer(release("0.3.0", "Joey64-0.3.0.apk", notes = "Save manager."), "0.2.0", testing = false)!!
        assertEquals("0.3.0", r.version)
        assertEquals("https://example.test/Joey64-0.3.0.apk", r.url)
        assertEquals(1234L, r.bytes)
        assertEquals("Save manager.", r.notes)
        assertFalse(r.testing)
    }

    @Test fun nothingWhenInstalledIsTheSameOrNewer() {
        assertNull(Releases.newer(release("0.3.0", "Joey64-0.3.0.apk"), "0.3.0", testing = false))
        assertNull(Releases.newer(release("0.3.0", "Joey64-0.3.0.apk"), "0.3.0.2", testing = false))
    }

    @Test fun aReleaseWithoutOurApkIsIgnored() {
        assertNull(Releases.newer(release("0.3.0", "source.zip"), "0.2.0", testing = false))
        assertNull(Releases.newer(release("0.3.0", null), "0.2.0", testing = false))
        assertEquals("0.3.0", Releases.newer(release("0.3.0", "joey64-v0.3.0.APK"), "0.2.0", testing = false)?.version)
    }

    @Test fun testingBuildsTakeTheNewestOfTheList() {
        val list = "[" + listOf(
            release("0.3.0", "Joey64-0.3.0.apk"),
            release("0.3.0.2", "Joey64-0.3.0.2.apk", pre = true),
            release("0.3.0.3", "Joey64-0.3.0.3.apk", pre = true, draft = true),
            release("0.2.0", "Joey64-0.2.0.apk"),
        ).joinToString(",") + "]"
        val r = Releases.newer(list, "0.3.0", testing = true)!!
        assertEquals("0.3.0.2", r.version)  // the draft isn't offered
        assertTrue(r.testing)
        assertNull(Releases.newer(list, "0.3.0.2", testing = true))
    }

    @Test fun megabytesRound() {
        assertEquals(28L, Releases.megabytes(28L * 1024 * 1024 + 100))
        assertEquals(12L, Releases.megabytes(12L * 1024 * 1024 - 100))
    }
}
