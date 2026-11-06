package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The ROM reader against real dumps: every zip in `<JOEY64_TESTDATA>/BigEndian` and
 * `/ByteSwapped` (the No-Intro sets in joey64/devtools/testdata/roms) is read and compared with
 * the `.z64` entry of the same game in a No-Intro / libretro DAT (`JOEY64_DAT`): MD5, CRC-32 and
 * serial (= header game code). Skipped unless both are set; it reads about 30 GB, so it isn't
 * part of the normal test run.
 */
class RealRomsCheck {
    private data class DatRom(val size: Long, val md5: String, val crc: String, val serial: String?)

    private fun dat(file: File): Map<String, DatRom> {
        val rom = Regex("""rom \( name "(.+?)\.z64" size (\d+) crc ([0-9A-F]{8}) md5 ([0-9A-F]{32})[^)]*?(?:serial "([^"]*)")?\s*\)""")
        return rom.findAll(file.readText()).associate { m ->
            m.groupValues[1] to DatRom(m.groupValues[2].toLong(), m.groupValues[4].lowercase(), m.groupValues[3], m.groupValues[5].ifEmpty { null })
        }
    }

    @Test fun noIntroSetsMatchTheDat() {
        val data = System.getenv("JOEY64_TESTDATA")?.let(::File)
        val datFile = System.getenv("JOEY64_DAT")?.let(::File)
        assumeTrue("set JOEY64_TESTDATA and JOEY64_DAT", data?.isDirectory == true && datFile?.isFile == true)
        val known = dat(datFile!!)
        val report = StringBuilder("DAT ${datFile.name}: ${known.size} .z64 entries\n")
        var bad = 0
        for (set in listOf("BigEndian", "ByteSwapped")) {
            var ok = 0; var missing = 0; var serialDiffers = 0; var otherSize = 0; var notRoms = 0
            val zips = File(data, set).listFiles { f -> f.name.endsWith(".zip") }!!.sortedBy { it.name }
            for (zip in zips) {
                val game = zip.name.removeSuffix(".zip")
                val a = zip.inputStream().buffered().use(ArchiveReader::readZip)
                val want = known[game]
                // The zip's own CRC of the entry: in the BigEndian set the file is in .z64 order, so
                // it must equal the CRC the reader worked out, whatever the DAT says.
                val stored = ZipFile(zip).use { z -> z.entries().toList().firstOrNull { !it.isDirectory } }
                if (a != null && set == "BigEndian" && "%08X".format(stored!!.crc) != a.info.crc32) {
                    bad++; report.append("$set: CRC differs from the zip's: $game\n")
                }
                when {
                    // BIOS and cheat-cart dumps aren't games (the ByteSwapped set names two PIF
                    // dumps .v64; they're 1984-byte boot ROMs); anything else unread is a bug.
                    a == null && (stored?.name?.endsWith(".bin") == true || game.startsWith("[BIOS]")) -> notRoms++
                    a == null -> { bad++; report.append("$set: not read: ${zip.name}\n") }
                    want == null -> missing++
                    // An older dump of the same game at another size (32 MiB overdumps of
                    // prototypes the DAT now lists trimmed): not comparable with the DAT.
                    a.info.size != want.size -> {
                        otherSize++; report.append("$set: other dump size: $game ${a.info.size} vs ${want.size}\n")
                    }
                    a.info.md5 != want.md5 || a.info.crc32 != want.crc -> {
                        bad++; report.append("$set: hash differs: $game md5 ${a.info.md5} vs ${want.md5}, crc ${a.info.crc32} vs ${want.crc}\n")
                    }
                    else -> {
                        ok++
                        if (want.serial != null && want.serial != a.info.header.gameCode) {
                            serialDiffers++
                            if (serialDiffers <= 15) report.append("$set: serial ${want.serial} vs header ${a.info.header.gameCode}: $game\n")
                        }
                    }
                }
            }
            report.append("$set: ${zips.size} zips, $ok match the DAT, $missing not in it, $otherSize another dump size, $notRoms not games (BIOS, .bin), $serialDiffers serials differ from the header\n")
        }
        File("build/real-roms-check.txt").writeText(report.toString())
        println(report)
        assertEquals("unreadable or mismatched ROMs (build/real-roms-check.txt)", 0, bad)
    }
}
