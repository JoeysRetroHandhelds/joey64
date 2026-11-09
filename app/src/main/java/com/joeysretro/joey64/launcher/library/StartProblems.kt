package com.joeysretro.joey64.launcher.library

/**
 * The rules behind the library's problem popups (approved problem screens, scratchpad build_problems2.py / problems2_mock.png,
 * "Approve as drawn" 2026-11-08). Plain functions, so they're unit-tested.
 */
object StartProblems {
    private val EXTENSIONS = setOf("z64", "n64", "v64", "zip", "7z", "ndd", "d64")

    /** "Body Harvest (USA)" for "Body Harvest (USA).zip" (only a game file's own extension comes off). */
    fun baseName(file: String): String {
        val dot = file.lastIndexOf('.')
        return if (dot > 0 && file.substring(dot + 1).lowercase() in EXTENSIONS) file.substring(0, dot) else file
    }

    /**
     * Screen 1: the file "looks damaged" only when it has a No-Intro name ("Body Harvest (USA)") but not that copy's MD5.
     * A known copy, a hack or a translation (never in the list) gets "Joey64 couldn't start it" instead.
     * [names] is the No-Intro table's names, [md5s] its MD5s.
     */
    fun looksDamaged(md5: String, fileName: String, md5s: Set<String>, names: Set<String>): Boolean =
        md5 !in md5s && baseName(fileName) in names

    /** The folders a file is in, from its document id ("primary:ROMs/n64/Body Harvest (USA).zip" -> ROMs, n64). */
    fun foldersOf(documentId: String): List<String> =
        documentId.substringAfter(':', "").split('/').filter { it.isNotEmpty() }.dropLast(1)
}
