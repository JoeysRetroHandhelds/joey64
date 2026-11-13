package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * FzSaves against real M64Plus FZ game data: every `<game> <MD5>/SramData` folder in `JOEY64_FZ_DATA` is turned into
 * our .srm (each part in its region, the rest the core's blank), and every `.st<n>` / `.v2.sav` under it unzipped into
 * a state our core loads. With `JOEY64_FZ_OUT` set, writes `<MD5>.srm` and `<file>.raw` there for a device check.
 * Skipped unless `JOEY64_FZ_DATA` is set: the files are Joey's own saves, not in the repo.
 */
class RealFzSavesCheck {
    @Test fun realFzSavesConvert() {
        val data = System.getenv("JOEY64_FZ_DATA")?.let(::File)
        assumeTrue("set JOEY64_FZ_DATA", data?.isDirectory == true)
        val out = System.getenv("JOEY64_FZ_OUT")?.let(::File)?.also { it.mkdirs() }
        val report = StringBuilder()
        var games = 0
        for (dir in data!!.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val md5 = FzSaves.romMd5(dir.name) ?: continue
            val files = File(dir, "SramData").listFiles().orEmpty().mapNotNull { f -> FzSaves.Part.of(f.name)?.let { it to f } }
            if (files.isEmpty()) continue
            val parts = files.associate { (p, f) -> p to f.readBytes() }
            val srm = FzSaves.toSrm(parts)
            assertEquals(FzSaves.SRM_SIZE, srm.size)
            for ((p, b) in parts) assertArrayEquals("${dir.name} ${p.ext}", b, srm.copyOfRange(p.at, p.at + b.size))
            out?.let { File(it, "$md5.srm").writeBytes(srm) }
            report.append("$md5 ${dir.name}: ").append(parts.entries.joinToString { "${it.key.ext} ${it.value.size}" }).append('\n')
            games++
        }
        for (f in data.walkTopDown().filter { it.isFile && (Regex("""\.st\d$""").containsMatchIn(it.name) || it.name.endsWith(".v2.sav")) }) {
            val raw = f.inputStream().buffered().use { FzSaves.stateForCore(it) }
            assertNotNull("${f.name} didn't unzip into a state our core reads", raw)
            val info = FzSaves.stateInfo(raw!!)!!
            out?.let { File(it, f.name + ".raw").writeBytes(raw) }
            report.append("state ${f.name}: version %08x, ROM ${info.romMd5}, ${raw.size} bytes\n".format(info.version))
        }
        println(report)
        assumeTrue("no FZ game folders in $data", games > 0)
    }
}
