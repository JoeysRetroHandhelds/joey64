package com.joeysretro.joey64.launcher.screens

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/**
 * One project Joey64 ships or uses, as Settings › About › Open-source licences lists it (approved
 * About mockup). [files] are in `assets/licenses/`, shown one after the other: the project's own
 * notice first, then the shared licence text it points at (gpl-2.0.txt, apache-2.0.txt, ...).
 */
data class OssEntry(
    val group: String,
    val name: String,
    /** Short, for the list ("GPL 2.0+"). */
    val licence: String,
    /** Long, for the text popup's subtitle ("GPL 2.0 or later"). */
    val licenceLong: String,
    val url: String,
    val files: List<String>,
    val note: String? = null,
)

/**
 * Everything in the APK that isn't Joey64's own, from the core build (`Makefile.common`,
 * `libretro/jni/Android.mk`: what HAVE_PARALLEL_RSP / RDP compile in), the host's
 * `third_party/`, the gradle dependencies, the bundled drivers, font and icons, and the data
 * the app's tables are made from. Keep it in step when a dependency comes or goes.
 * angrylion is not in the core since a8ab829 (its licence is non-commercial), so it isn't listed.
 */
val OSS_ENTRIES = listOf(
    OssEntry("Emulator", "mupen64plus-core", "GPL 2.0+", "GPL 2.0 or later", "github.com/mupen64plus/mupen64plus-core",
        listOf("mupen64plus.txt", "gpl-2.0.txt"), "with Mupen64Plus-Next, RSP HLE"),
    OssEntry("Emulator", "parallel-RDP", "MIT", "MIT", "github.com/Themaister/parallel-rdp",
        listOf("parallel-rdp.txt", "apache-2.0.txt")),
    OssEntry("Emulator", "parallel-RSP", "MIT / LGPL 3.0", "MIT or LGPL 3.0", "github.com/Themaister/parallel-rsp",
        listOf("parallel-rsp.txt", "lgpl-3.0.txt")),
    OssEntry("Emulator", "GNU lightning", "LGPL 3.0+", "LGPL 3.0 or later", "www.gnu.org/software/lightning",
        listOf("lightning.txt", "lgpl-3.0.txt", "gpl-3.0.txt"), "in parallel-RSP"),
    OssEntry("Emulator", "CXD4 RSP", "CC0 1.0", "CC0 1.0", "github.com/mupen64plus/mupen64plus-rsp-cxd4",
        listOf("cxd4.txt")),
    OssEntry("Emulator", "GLideN64", "GPL 2.0", "GPL 2.0", "github.com/gonetz/GLideN64",
        listOf("gliden64.txt", "gpl-2.0.txt")),
    OssEntry("Emulator", "xBRZ", "GPL 3.0", "GPL 3.0", "sourceforge.net/projects/xbrz",
        listOf("xbrz.txt", "gpl-3.0.txt"), "in GLideN64"),
    OssEntry("Emulator", "libpng", "libpng", "libpng licence", "libpng.org",
        listOf("libpng.txt"), "in GLideN64"),
    OssEntry("Emulator", "xxHash", "BSD 2-Clause", "BSD 2-Clause", "github.com/Cyan4973/xxHash",
        listOf("xxhash.txt")),
    OssEntry("Emulator", "zlib, minizip, MD5", "zlib", "zlib licence", "zlib.net",
        listOf("zlib.txt")),
    OssEntry("Emulator", "libretro-common", "MIT", "MIT", "github.com/libretro/libretro-common",
        listOf("libretro-common.txt")),
    OssEntry("Emulator", "ares", "ISC", "ISC", "ares-emu.net",
        listOf("ares.txt"), "save types by game"),
    OssEntry("Emulator", "rcheevos", "MIT", "MIT", "github.com/RetroAchievements/rcheevos",
        listOf("rcheevos.txt"), "RetroAchievements"),
    OssEntry("App", "miniupnpc", "BSD 3-Clause", "BSD 3-Clause", "miniupnp.tuxfamily.org",
        listOf("miniupnpc.txt"), "Play together over the internet"),
    OssEntry("App", "Kotlin, AndroidX", "Apache 2.0", "Apache 2.0", "developer.android.com/jetpack",
        listOf("androidx.txt", "apache-2.0.txt"), "with Compose"),
    OssEntry("App", "Commons Compress", "Apache 2.0", "Apache 2.0", "commons.apache.org/proper/commons-compress",
        listOf("apache-commons.txt", "apache-2.0.txt"), ".7z games"),
    OssEntry("App", "XZ for Java", "0BSD", "0BSD", "tukaani.org/xz/java.html",
        listOf("xz-java.txt"), ".7z games"),
    OssEntry("App", "Turnip (Mesa)", "MIT", "MIT", "mesa3d.org",
        listOf("mesa.txt"), "Vulkan driver"),
    OssEntry("App", "libadrenotools", "BSD 2-Clause", "BSD 2-Clause", "github.com/bylaws/libadrenotools",
        listOf("libadrenotools.txt"), "loads the driver"),
    OssEntry("App", "FidelityFX CAS, FSR 1", "MIT", "MIT", "github.com/GPUOpen-Effects",
        listOf("amd-fidelityfx.txt"), "Sharpening"),
    OssEntry("App", "Rubik font", "OFL 1.1", "SIL Open Font License 1.1", "github.com/googlefonts/rubik",
        listOf("rubik-ofl.txt")),
    OssEntry("App", "Tabler Icons", "MIT", "MIT", "tabler.io/icons",
        listOf("tabler-icons.txt")),
    OssEntry("Data", "libretro-database", "CC BY-SA 4.0", "CC BY-SA 4.0", "github.com/libretro/libretro-database",
        listOf("libretro-database.txt"), "game names, players"),
    OssEntry("Data", "RetroArch autoconfig", "MIT", "MIT", "github.com/libretro/retroarch-joypad-autoconfig",
        listOf("retroarch-autoconfig.txt"), "controllers"),
    OssEntry("Data", "SDL_GameControllerDB", "zlib", "zlib licence", "github.com/mdqinc/SDL_GameControllerDB",
        listOf("sdl-gamecontrollerdb.txt"), "controllers"),
)

/** Joey64's own licence, for About's "Joey64 is free software" row. */
val JOEY64_LICENCE = OssEntry("", "Joey64", "GPL 3.0+", "GPL 3.0 or later", "github.com/JoeysRetroHandhelds/joey64",
    listOf("joey64.txt", "gpl-3.0.txt"))

/** An entry's text: its files one after the other, line endings made plain. */
fun ossText(context: Context, entry: OssEntry): String = entry.files.joinToString("\n\n\n") { f ->
    try { context.assets.open("licenses/$f").bufferedReader().use { it.readText() }.replace("\r\n", "\n").replace("\t", "    ").trimEnd() }
    catch (e: Exception) { "(licenses/$f is missing from this build)" }
}

/** The list popup: Emulator on the left, App and Data on the right; A opens an entry's text. */
@Composable
fun OssListPopup(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<OssEntry?>(null) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Read licence"), Hint("D-pad", "Move"), Hint("B", "Back")), width = 600.dp) {
        Column {
            PopupTitle("Open-source licences", "Select one to read its licence text and copyright notice.")
            val groups = OSS_ENTRIES.groupBy { it.group }
            @Composable
            fun group(g: String) {
                SectionLabel(g)
                for (e in groups[g].orEmpty()) {
                    JoeyRow(e.name, Modifier.initialFocus(e === OSS_ENTRIES.first()), value = e.licence, detail = e.note,
                        sideways = true) { reading = e }
                }
            }
            com.joeysretro.joey64.launcher.components.TwoColumns(Modifier.heightIn(max = 330.dp).weight(1f, fill = false), gap = 16.dp,
                left = { group("Emulator") }) { group("App"); group("Data") }
        }
    }
    reading?.let { LicenceTextPopup(it) { reading = null } }
}

/** One licence: the notice and licence text in a box the D-pad scrolls (approved mockup). */
@Composable
fun LicenceTextPopup(entry: OssEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(entry) { ossText(context, entry) }
    TextBoxPopup(entry.name, "${entry.licenceLong} · ${entry.url}", text, onDismiss)
}

/** The scrolling text box (licences, Settings › About › Send reports › What's sent): D-pad, L1 / R1 a page. */
@Composable
fun TextBoxPopup(title: String, subtitle: String?, text: String, onDismiss: () -> Unit) {
    val c = JoeyTheme.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // L1 / R1 a page up or down (pad buttons reach a popup only through onPadButton).
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("D-pad", "Scroll"), Hint("B", "Back")), width = 620.dp,
        onPadButton = { k ->
            if (k == KeyEvent.KEYCODE_BUTTON_R1) scope.launch { scroll.animateScrollBy(600f) }
            if (k == KeyEvent.KEYCODE_BUTTON_L1) scope.launch { scroll.animateScrollBy(-600f) }
        }) {
        Column {
            PopupTitle(title, subtitle)
            Box(
                Modifier
                    .padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 4.dp)
                    .fillMaxWidth().height(250.dp)
                    .background(c.page, RoundedCornerShape(8.dp))
                    .drawWithContent {
                        drawContent()
                        val max = scroll.maxValue
                        if (max > 0 && max != Int.MAX_VALUE) {
                            val h = size.height * size.height / (size.height + max)
                            val y = (size.height - h) * scroll.value / max
                            drawRoundRect(c.textFaint, Offset(size.width - 5.dp.toPx(), y), Size(3.dp.toPx(), h), CornerRadius(2.dp.toPx()))
                        }
                    }
                    .initialFocus()
                    .onPreviewKeyEvent { ev ->
                        val step = when (ev.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> 120f
                            KeyEvent.KEYCODE_DPAD_UP -> -120f
                            KeyEvent.KEYCODE_DPAD_RIGHT -> 600f
                            KeyEvent.KEYCODE_DPAD_LEFT -> -600f
                            else -> return@onPreviewKeyEvent false
                        }
                        if (ev.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step) }
                        true
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text, style = JoeyTheme.type.small, color = c.textSecondary)
            }
        }
    }
}
