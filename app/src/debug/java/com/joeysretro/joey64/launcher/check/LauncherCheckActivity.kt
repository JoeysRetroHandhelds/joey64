package com.joeysretro.joey64.launcher.check

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyPage
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.KeyNormaliser
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.library.LibraryScanner
import com.joeysretro.joey64.launcher.layout.LocalScreenLayout
import com.joeysretro.joey64.launcher.layout.ProvideScreenLayout
import com.joeysretro.joey64.launcher.settings.KeystoreBox
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.settings.SignIn
import com.joeysretro.joey64.launcher.settings.SignInStore
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.DataMover
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Joey64Check"

/** Test files the checks write; nothing else is ever touched by "Remove test data". */
private const val TEST_NAME = "joey64-check"

/**
 * Phase 0 device checks, debug builds only (src/debug). One page of rows, each runs one check and
 * shows its result on the right; every result is also logged (`adb logcat -s Joey64Check`).
 * Built only from the shared components (JoeyPage, JoeyDialog, JoeyRow), so the checks exercise
 * the same shells the launcher will use. Not a design: a dev page until the Phase 1 screens.
 */
// RestrictedApi: androidx.core's ComponentActivity marks its override of the public
// Activity.dispatchKeyEvent as library-internal, so lint flags every override and super call.
// Overriding it is how an activity takes its keys (JoeyOS's MainActivity does the same); the
// launcher's own activity will need this too.
@SuppressLint("RestrictedApi")
class LauncherCheckActivity : ComponentActivity() {
    private val keys = KeyNormaliser { super.dispatchKeyEvent(it) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { CheckScreen(onClose = ::finish) }
    }
}

private enum class OpenPopup { None, Plain, MustAnswer }

@Composable
private fun CheckScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val results = remember { mutableStateMapOf<String, String>() }
    var theme by remember { mutableStateOf(ThemeChoice.Dark) }
    var popup by remember { mutableStateOf(OpenPopup.None) }
    var dataTree by remember { mutableStateOf<Uri?>(null) }

    fun report(key: String, text: String) {
        results[key] = text
        Log.i(TAG, "$key: $text")
    }

    /** Runs a check off the main thread; an exception is a failed check, not a crash. */
    fun run(key: String, block: suspend () -> String) {
        report(key, "running")
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Throwable) {   // a StackOverflowError too: a failed check, not a crash
                Log.e(TAG, "$key failed", e)
                "FAILED: ${e.javaClass.simpleName}: ${e.message}"
            }
            report(key, text)
        }
    }

    LaunchedEffect(Unit) {
        val s = settings.settings.first()
        dataTree = s.dataFolder?.toUri()
        theme = s.theme
        Log.i(TAG, "saved settings: $s")
    }

    val pickRoms = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { report("roms", "picker closed, nothing picked"); return@rememberLauncherForActivityResult }
        run("roms") {
            RomFolders.keep(context, uri, write = false)
            settings.addRomFolder(uri.toString())
            val t0 = SystemClock.elapsedRealtime()
            val roms = RomFolders.scan(context.contentResolver, uri)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "picked ${DocumentsContract.getTreeDocumentId(uri)}; first ROMs: ${roms.take(5).map { it.path.joinToString("/") }}")
            "${roms.size} ROMs in $ms ms"
        }
    }

    val pickData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { report("data", "picker closed, nothing picked"); return@rememberLauncherForActivityResult }
        run("data") {
            RomFolders.keep(context, uri, write = true)
            settings.setDataFolder(uri.toString())
            dataTree = uri
            DocumentsContract.getTreeDocumentId(uri)
        }
    }

    JoeyTheme(theme) {
        ProvideScreenLayout(Modifier.fillMaxSize()) {
            val layout = LocalScreenLayout.current
            JoeyPage(
                onClose = onClose,
                hints = listOf(Hint("A", "Run"), Hint("B", "Close")),
                onControl = { control -> report("button", control.name); true },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = JoeyTheme.spacing.margin, vertical = 8.dp),
                ) {
                    Text("Launcher checks", style = JoeyTheme.type.pageTitle, color = JoeyTheme.colors.text,
                        modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, bottom = 4.dp))

                    SectionLabel("Screen and buttons")
                    JoeyRow(
                        "${layout.shape} screen, ${layout.boxArtColumns} box-art columns",
                        modifier = Modifier.initialFocus(),
                        value = "${layout.widthDp.toInt()} x ${layout.heightDp.toInt()} dp",
                    ) { Log.i(TAG, "layout: $layout") }
                    JoeyRow(
                        "Settings in ${if (layout.singleColumnSettings) "one column" else "two columns"}",
                        value = if (layout.bottomBar) "bottom bar" else "side rail",
                    )
                    JoeyRow("Theme", value = theme.name) {
                        theme = if (theme == ThemeChoice.Dark) ThemeChoice.Light else ThemeChoice.Dark
                        Log.i(TAG, "theme: $theme")
                    }
                    JoeyRow("Last launcher button (Start, X, Y, L, R)", value = results["button"] ?: "none yet")

                    SectionLabel("Popups")
                    JoeyRow("Open a popup", value = results["popup"]) { popup = OpenPopup.Plain }
                    JoeyRow("Open a popup that must be answered", value = results["mustAnswer"]) { popup = OpenPopup.MustAnswer }
                    val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        (context as Activity).windowManager.isCrossWindowBlurEnabled else false
                    JoeyRow("Background blur behind popups", value = if (blur) "available" else "not available (dim only)")

                    SectionLabel("ROM folders")
                    JoeyRow("Pick a ROMs folder", value = results["roms"]) { pickRoms.launch(RomFolders.initialPickerUri(context)) }
                    JoeyRow("Scan the saved ROM folders", value = results["scan"]) {
                        run("scan") {
                            val folders = settings.settings.first().romFolders
                            if (folders.isEmpty()) return@run "no ROM folders saved"
                            folders.joinToString("; ") { s ->
                                val uri = s.toUri()
                                if (!RomFolders.stillReadable(context, uri)) "${DocumentsContract.getTreeDocumentId(uri)}: permission gone"
                                else {
                                    val t0 = SystemClock.elapsedRealtime()
                                    val n = RomFolders.scan(context.contentResolver, uri).size
                                    "$n ROMs in ${SystemClock.elapsedRealtime() - t0} ms"
                                }
                            }
                        }
                    }
                    JoeyRow("Read the saved ROM folders into the library", value = results["library"]) {
                        run("library") {
                            val folders = settings.settings.first().romFolders.map { it.toUri() }
                            if (folders.isEmpty()) return@run "no ROM folders saved"
                            val r = LibraryScanner(context, LibraryDb.get(context)).scan(folders) { done, total ->
                                if (done % 10 == 0 || done == total) results["library"] = "reading $done of $total"
                            }
                            "${r.games} games; ${r.files} files: ${r.read} read, ${r.unchanged} unchanged, ${r.notRoms} not ROMs, " +
                                "${r.gone} gone, ${r.failed} failed; ${r.foldersSkipped} of ${r.folders} folders skipped; " +
                                "list ${r.listMillis} ms, read ${r.readMillis} ms"
                        }
                    }
                    JoeyRow("Where the ROMs picker opens",value = results["see"]) { run("see") { pickerStart(context) } }

                    SectionLabel("Data folder")
                    JoeyRow("Pick a data folder", value = results["data"] ?: dataTree?.let { DocumentsContract.getTreeDocumentId(it) }) {
                        pickData.launch(null)
                    }
                    JoeyRow("Make test data", value = results["make"]) {
                        val tree = dataTree ?: return@JoeyRow report("make", "pick a data folder first")
                        run("make") { makeTestData(DataFolder.internal(context), DataFolder.picked(context, tree)) }
                    }
                    JoeyRow("Move data to the picked folder", value = results["moveOut"]) {
                        val tree = dataTree ?: return@JoeyRow report("moveOut", "pick a data folder first")
                        run("moveOut") { moveOut(DataFolder.internal(context), DataFolder.picked(context, tree)) }
                    }
                    JoeyRow("Move data back to Joey64", value = results["moveBack"]) {
                        val tree = dataTree ?: return@JoeyRow report("moveBack", "pick a data folder first")
                        run("moveBack") { moveBack(DataFolder.picked(context, tree), DataFolder.internal(context)) }
                    }
                    JoeyRow("Remove test data", value = results["clean"]) {
                        run("clean") {
                            val spaces = listOfNotNull(DataFolder.internal(context), dataTree?.let { DataFolder.picked(context, it) })
                            "${spaces.sumOf { removeTestData(it) }} test files removed"
                        }
                    }

                    SectionLabel("Sign-in and library")
                    JoeyRow("Keystore round trip", value = results["keystore"]) { run("keystore") { keystoreCheck(SignInStore(context)) } }
                    JoeyRow("Library database", value = results["db"]) { run("db") { databaseCheck(LibraryDb.get(context)) } }
                }
            }

            when (popup) {
                OpenPopup.None -> {}
                OpenPopup.Plain -> JoeyDialog(
                    onDismiss = { report("popup", "closed with B or Back"); popup = OpenPopup.None },
                    hints = listOf(Hint("A", "Pick"), Hint("B", "Back")),
                    onOptions = { report("popup", "Start pressed in the popup") },
                ) {
                    Column {
                        PopupTitle("A popup", "B, Back or a tap outside closes it")
                        JoeyOption("First option", Modifier.initialFocus()) { report("popup", "picked First"); popup = OpenPopup.None }
                        JoeyOption("Second option", value = "a value") { report("popup", "picked Second"); popup = OpenPopup.None }
                        JoeyOption("Locked option", enabled = false) { report("popup", "a locked option ran (wrong)") }
                        JoeyOption("Third option") { report("popup", "picked Third"); popup = OpenPopup.None }
                        PopupNote("Focus should start on First and stay inside the popup.")
                    }
                }
                OpenPopup.MustAnswer -> JoeyDialog(
                    onDismiss = { report("mustAnswer", "closed without an answer (wrong)"); popup = OpenPopup.None },
                    hints = listOf(Hint("A", "Pick")),
                    dismissible = false,
                ) {
                    Column {
                        PopupTitle("Must be answered", "B, Back and a tap outside do nothing")
                        JoeyOption("Yes", Modifier.initialFocus()) { report("mustAnswer", "answered Yes"); popup = OpenPopup.None }
                        JoeyOption("No") { report("mustAnswer", "answered No"); popup = OpenPopup.None }
                    }
                }
            }
        }
    }
}

private fun testFile(kind: DataKind) = when (kind) {
    DataKind.Saves -> listOf("saves", "$TEST_NAME.srm")
    DataKind.States -> listOf("states", "$TEST_NAME.state1")
    DataKind.Screenshots -> listOf("screenshots", TEST_NAME, "shot 1.png")
    DataKind.Textures -> listOf("textures", "$TEST_NAME.hts")
    DataKind.Cheats -> listOf("cheats", "$TEST_NAME.cht")
    DataKind.BoxArt -> listOf("boxart", "$TEST_NAME.png")
}

private fun testBytes(kind: DataKind) = ByteArray(40_000 + kind.ordinal * 1_000) { i -> (i * 31 + kind.ordinal).toByte() }

/** A save left half copied by an earlier, cut-off move, and a different file in the way of one name. */
private val PART = listOf("saves", "$TEST_NAME.srm${DataMover.PART_SUFFIX}")
private val CONFLICT = listOf("cheats", "$TEST_NAME-conflict.cht")

/** One test file per kind in Joey64's storage; a half-written copy and a name clash in the picked folder. */
private fun makeTestData(internal: DataFolder, picked: DataFolder): String {
    for (kind in DataKind.entries) internal.space.openWrite(testFile(kind)).use { it.write(testBytes(kind)) }
    internal.space.openWrite(CONFLICT).use { it.write("joey64's copy".toByteArray()) }
    picked.space.openWrite(PART).use { it.write(testBytes(DataKind.Saves), 0, 1_000) }
    picked.space.openWrite(CONFLICT).use { it.write("the picked folder's own copy".toByteArray()) }
    val others = DataKind.entries.sumOf { k -> internal.list(k).count { !it.name.startsWith(TEST_NAME) } }
    return "7 files in Joey64, 2 in the folder; $others other files in Joey64"
}

private fun readAll(folder: DataFolder, path: List<String>) = folder.space.openRead(path)?.use { it.readBytes() }

private fun moveOut(internal: DataFolder, picked: DataFolder): String {
    check(internal.canMoveTo(picked)) { "canMoveTo said no" }
    val t0 = SystemClock.elapsedRealtime()
    val r = internal.moveTo(picked)
    val ms = SystemClock.elapsedRealtime() - t0
    Log.i(TAG, "moveOut: $r")
    val problems = mutableListOf<String>()
    for (kind in DataKind.entries) {
        if (!readAll(picked, testFile(kind)).contentEquals(testBytes(kind))) problems += "${kind.dir} differs"
        if (internal.space.exists(testFile(kind))) problems += "${kind.dir} still in Joey64"
        if (kind.hiddenFromGallery != picked.space.exists(listOf(kind.dir, DataKind.NO_MEDIA))) problems += "${kind.dir} .nomedia wrong"
    }
    if (picked.space.exists(PART)) problems += "half copy left"
    if (r.conflicts != listOf(CONFLICT.joinToString("/"))) problems += "conflicts ${r.conflicts}"
    if (String(readAll(picked, CONFLICT) ?: ByteArray(0)) != "the picked folder's own copy") problems += "clash overwritten"
    if (!internal.space.exists(CONFLICT)) problems += "clashing file left Joey64"
    // The emulator side will take saves as descriptors: one must open and report the right size.
    val fdSize = picked.openFd(DataKind.Saves, "$TEST_NAME.srm", "r")?.use { Os.fstat(it.fileDescriptor).st_size }
    if (fdSize != testBytes(DataKind.Saves).size.toLong()) problems += "descriptor size $fdSize"
    Log.i(TAG, "moveOut problems: $problems")
    return if (problems.isEmpty()) "OK: ${r.moved} moved, 1 clash kept, $ms ms" else "FAILED: ${problems.joinToString(", ")}"
}

private fun moveBack(picked: DataFolder, internal: DataFolder): String {
    val r = picked.moveTo(internal)
    Log.i(TAG, "moveBack: $r")
    val back = DataKind.entries.count { readAll(internal, testFile(it)).contentEquals(testBytes(it)) }
    return "${r.moved} moved, $back of 6 test files back, clashes ${r.conflicts.size}"
}

/** Deletes only this check's own files, then any folder they leave empty. */
private fun removeTestData(folder: DataFolder): Int {
    var n = 0
    /** [ours]: inside a folder this check made (screenshots/joey64-check/), where every file is a test file. */
    fun sweep(dir: List<String>, ours: Boolean = false) {
        for (e in folder.space.list(dir)) {
            val path = dir + e.name
            if (e.isDir) {
                sweep(path, ours || e.name.startsWith(TEST_NAME))
                if (folder.space.list(path).isEmpty()) folder.space.delete(path)
            } else if (ours || e.name.startsWith(TEST_NAME)) {
                if (folder.space.delete(path)) n++
            }
        }
    }
    for (kind in DataKind.entries) {
        val dir = listOf(kind.dir)
        sweep(dir)
        // A folder holding nothing but its .nomedia was made by these checks: remove both.
        if (folder.space.list(dir).all { it.name == DataKind.NO_MEDIA }) {
            folder.space.delete(dir + DataKind.NO_MEDIA)
            folder.space.delete(dir)
        }
    }
    return n
}

private suspend fun keystoreCheck(store: SignInStore): String {
    val token = "check-" + SystemClock.elapsedRealtimeNanos()
    store.save(SignIn("joey64check", token))
    val back = store.load()
    store.clear()
    val tampered = KeystoreBox.open(KeystoreBox.seal(byteArrayOf(1, 2, 3)).let { s -> s.dropLast(4) + "AAAA" })
    val sealedTwice = KeystoreBox.seal(token.toByteArray()) != KeystoreBox.seal(token.toByteArray())
    val ok = back?.token == token && tampered == null && sealedTwice && store.load() == null
    return if (ok) "OK: sealed, read back, tampering refused" else "FAILED: back=${back?.token == token} tampered=${tampered == null} freshIv=$sealedTwice"
}

private fun databaseCheck(lib: LibraryDb): String {
    val db = lib.writableDatabase
    val version = db.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
    val wal = db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
    val fk = db.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); it.getInt(0) }
    val md5 = "$TEST_NAME-${System.currentTimeMillis()}"
    val id = db.insertOrThrow("games", null, android.content.ContentValues().apply {
        put("md5", md5); put("crc32", "00000000"); put("title", "Joey64 check"); put("added_at", System.currentTimeMillis())
    })
    lib.recordPlay(id, System.currentTimeMillis(), 90)
    lib.recordPlay(id, System.currentTimeMillis(), 30)
    val seconds = db.rawQuery("SELECT play_seconds FROM games WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst(); it.getLong(0) }
    db.delete("games", "id = ?", arrayOf(id.toString()))
    val leftSessions = db.rawQuery("SELECT COUNT(*) FROM play_sessions WHERE game_id = ?", arrayOf(id.toString())).use { it.moveToFirst(); it.getLong(0) }
    val ok = seconds == 120L && leftSessions == 0L && fk == 1 && wal.equals("wal", ignoreCase = true)
    return "${if (ok) "OK" else "FAILED"}: SQLite $version, $wal, play time $seconds s, sessions left $leftSessions"
}

/** What [RomFolders.looks] sees on each volume, and where the picker opens because of it. */
private fun pickerStart(context: android.content.Context): String {
    val looks = RomFolders.looks(context)
    val start = DocumentsContract.getDocumentId(RomFolders.initialPickerUri(context))
    Log.i(TAG, "picker: $looks -> $start")
    return start
}
