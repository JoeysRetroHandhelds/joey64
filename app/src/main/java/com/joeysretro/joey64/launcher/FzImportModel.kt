package com.joeysretro.joey64.launcher

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.library.FzFound
import com.joeysretro.joey64.launcher.library.FzImport
import com.joeysretro.joey64.launcher.library.FzItem
import com.joeysretro.joey64.launcher.library.FzPlan
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.library.Progress
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolderRules
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.storage.TreeSpace
import com.joeysretro.joey64.states.GameStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where Import from M64Plus FZ is (approved mockup 2026-11-13, pages 2-6). */
sealed interface FzStep {
    /** "Copy your saves from M64Plus FZ": where FZ keeps its data, Choose FZ folder. */
    data object Offer : FzStep
    data object Looking : FzStep
    data class Found(val tree: Uri, val place: String, val folders: List<String>, val found: FzFound, val plan: FzPlan) : FzStep
    data class Copying(val saves: Int, val states: Int, val progress: Progress) : FzStep
    /** [saves], [states]: FZ's now in Joey64 (written, or the same already there); [kept]: clashes left as Joey64 had them. */
    data class Done(val saves: Int, val states: Int, val kept: Int, val failed: Int) : FzStep
}

/** Import from M64Plus FZ ([FzImport]): reads FZ's game data folder, writes into Joey64's data folder. */
class FzImportModel(app: Application) : AndroidViewModel(app) {
    private val _step = MutableStateFlow<FzStep>(FzStep.Offer)
    val step: StateFlow<FzStep> = _step

    fun restart() { _step.value = FzStep.Offer }

    /** From Found or Nothing found back to the first page. */
    fun back() { if (_step.value is FzStep.Found) _step.value = FzStep.Offer }

    /** The picker opens at internal storage's M64Plus folder, where FZ's guides put the external game data. */
    fun pickerUri(): Uri = DocumentsContract.buildDocumentUri(RomFolders.EXTERNAL_STORAGE, RomFolderRules.PRIMARY + ":M64Plus")

    fun look(tree: Uri, dataFolder: String?) {
        _step.value = FzStep.Looking
        viewModelScope.launch {
            val app = getApplication<Application>()
            _step.value = withContext(Dispatchers.IO) {
                val name = FolderNames.of(app, tree)
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val fz = TreeSpace(app.contentResolver, tree)
                    val found = FzImport.find(fz)
                    val plan = FzImport.plan(fz, found, saveNames(app, found.games.map { it.md5 }.toSet()), DataFolder.of(app, dataFolder).space)
                    JLog.i(TAG, "found ${found.games.size} FZ games in ${android.os.SystemClock.elapsedRealtime() - t0} ms (root ${found.root}): " +
                        "${plan.saves} saves, ${plan.states} states for the library; ${plan.toCopy.size} new, ${plan.alreadyThere.size} already there, " +
                        "${plan.clashes.size} clashes, ${plan.notInLibrary.size} games not in the library")
                    FzStep.Found(tree, name.place, name.folders, found, plan)
                } catch (e: Exception) {
                    JLog.w(TAG, "can't read $tree" + "\n" + Log.getStackTraceString(e))
                    FzStep.Found(tree, name.place, name.folders, FzFound(emptyList(), emptyList()), FzPlan(emptyList(), emptyList(), emptyList(), emptyList()))
                }
            }
        }
    }

    /** ROM MD5 (upper case) to the save name the game plays under, for the library games among [md5s]. */
    private fun saveNames(app: Application, md5s: Set<String>): Map<String, String> {
        val db = LibraryDb.get(app)
        val launcher = GameLauncher(app, db)
        return db.libraryGames().filter { it.md5.uppercase() in md5s }.mapNotNull { g ->
            val name = launcher.saveNameFor(g) ?: db.filesOf(g.id).firstOrNull()?.let { GameStates.saveNameOf(it.name) }
            name?.let { g.md5.uppercase() to it }
        }.toMap()
    }

    /** Writes what [f] found. [useFz]: the clashes answered "Use M64Plus FZ's"; the rest keep Joey64's. */
    fun copy(f: FzStep.Found, useFz: Set<FzItem>, dataFolder: String?) {
        if (_step.value != f) return
        val replace = f.plan.clashes.map { it.item }.filter { it in useFz }
        val all = f.plan.toCopy + replace
        fun bytes(x: FzItem) = if (x.kind == DataKind.Saves) x.game.parts.sumOf { it.size } else x.game.states.first { it.slot == x.slot }.size
        val totalBytes = all.sumOf(::bytes)
        val started = android.os.SystemClock.elapsedRealtime()
        var doneFiles = 0
        var doneBytes = 0L
        fun progress() = Progress(doneFiles, all.size, started, doneBytes, totalBytes)
        _step.value = FzStep.Copying(f.plan.saves, f.plan.states, progress())
        viewModelScope.launch {
            val app = getApplication<Application>()
            _step.value = withContext(Dispatchers.IO) {
                val fz = TreeSpace(app.contentResolver, f.tree)
                val joey = DataFolder.of(app, dataFolder)
                joey.hideFromGallery()
                val t0 = android.os.SystemClock.elapsedRealtime()
                var saves = 0
                var states = 0
                var failed = 0
                for (item in all) {
                    if (FzImport.write(fz, joey.space, item, replace = item in replace)) {
                        if (item.kind == DataKind.Saves) saves++ else states++
                    } else {
                        failed++
                        JLog.w(TAG, "not copied: ${item.game.folder} -> ${item.name}")
                    }
                    doneFiles++
                    doneBytes += bytes(item)
                    _step.value = FzStep.Copying(f.plan.saves, f.plan.states, progress())
                }
                saves += f.plan.alreadyThere.count { it.kind == DataKind.Saves }
                states += f.plan.alreadyThere.count { it.kind == DataKind.States }
                val kept = f.plan.clashes.size - replace.size
                JLog.i(TAG, "copied in ${android.os.SystemClock.elapsedRealtime() - t0} ms: $saves saves, $states states in Joey64, $failed failed, " +
                    "${replace.size} replaced, $kept kept")
                app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putLong("at", System.currentTimeMillis()).putInt("saves", saves).putInt("states", states).apply()
                FzStep.Done(saves, states, kept, failed)
            }
        }
    }

    /** The last import, for Settings › Saves' panel. */
    data class LastImport(val at: Long, val saves: Int, val states: Int)

    companion object {
        private const val TAG = "Joey64FzImport"
        private const val PREFS = "fz_import"

        fun lastImport(context: android.content.Context): LastImport? {
            val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val at = p.getLong("at", 0L).takeIf { it > 0 } ?: return null
            return LastImport(at, p.getInt("saves", 0), p.getInt("states", 0))
        }
    }
}
