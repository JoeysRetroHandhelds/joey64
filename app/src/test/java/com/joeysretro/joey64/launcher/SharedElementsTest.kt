package com.joeysretro.joey64.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One of each element (Joey, 2026-11-05): one popup whose contents change, one page, one set of
 * rows, one type scale. A change to the shell then reaches every popup, page and row at once.
 * Like Chameleon's SettingsRowConventionTest, this fails the build when code goes around them.
 * When it trips, use the shared element it names (or grow that element), rather than deleting
 * the check.
 *
 * Covers the launcher package only (the emulator's MainActivity is its own thing).
 */
class SharedElementsTest {

    private val root = File("src/main/java/com/joeysretro/joey64/launcher")

    private data class Source(val path: String, val code: String)

    /** Every launcher source, with comments taken out so a KDoc mentioning `Dialog(` doesn't count. */
    private val sources: List<Source> by lazy {
        assertTrue("launcher sources not found from ${File(".").absolutePath}", root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { f ->
            val code = f.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lines().joinToString("\n") { it.replace(Regex("//.*$"), "") }
            Source(f.relativeTo(root).invariantSeparatorsPath, code)
        }.toList()
    }

    private fun offenders(pattern: Regex, allowed: (String) -> Boolean) =
        sources.filter { !allowed(it.path) && pattern.containsMatchIn(it.code) }.map { it.path }

    @Test fun onePopupWindow() {
        // Every popup is JoeyDialog with its own contents; nothing opens another kind of window.
        val bad = offenders(Regex("""\b(Dialog|Popup|AlertDialog|BasicAlertDialog|ModalBottomSheet|DropdownMenu|DatePickerDialog)\s*\(""")) {
            it == "components/JoeyDialog.kt"
        }
        assertTrue("open popups with JoeyDialog, not their own window: $bad", bad.isEmpty())
    }

    @Test fun typeSizesOnlyInTheTheme() {
        // Text sizes, weights and families are the type scale in theme/Type.kt.
        val bad = offenders(Regex("""\bfontSize\s*=|\d\.sp\b|\d+\.sp\b|\bTextStyle\s*\(|\bFontFamily\s*\(""")) { it.startsWith("theme/") }
        assertTrue("use JoeyTheme.type instead of setting a text size here: $bad", bad.isEmpty())
    }

    @Test fun everyTextTakesAStyleFromTheTypeScale() {
        val bad = ArrayList<String>()
        for (s in sources) {
            if (s.path.startsWith("theme/")) continue
            for (args in calls(s.code, "Text")) if (!Regex("""\bstyle\s*=""").containsMatchIn(args)) bad += "${s.path}: Text(${args.take(40)}"
        }
        assertTrue("give every Text a style from JoeyTheme.type: $bad", bad.isEmpty())
    }

    @Test fun coloursOnlyInTheTheme() {
        // Colours come from JoeyTheme.colors, so dark and light can't drift apart.
        val bad = offenders(Regex("""\bColor\s*\(\s*0x""")) { it.startsWith("theme/") }
        assertTrue("use JoeyTheme.colors instead of a colour here: $bad", bad.isEmpty())
    }

    /** The argument lists of every call to [name] (balanced parentheses). */
    private fun calls(code: String, name: String): List<String> {
        val out = ArrayList<String>()
        val start = Regex("""(?<![\w.])$name\s*\(""")
        for (m in start.findAll(code)) {
            var depth = 1
            var i = m.range.last + 1
            while (i < code.length && depth > 0) {
                when (code[i]) { '(' -> depth++; ')' -> depth-- }
                i++
            }
            out += code.substring(m.range.last + 1, (i - 1).coerceAtLeast(m.range.last + 1))
        }
        return out
    }
}
