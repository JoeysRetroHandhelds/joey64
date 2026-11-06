package com.joeysretro.joey64.launcher

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joeysretro.joey64.launcher.library.RaFile
import com.joeysretro.joey64.launcher.library.RaFound
import com.joeysretro.joey64.launcher.library.RaImportPlan
import com.joeysretro.joey64.launcher.library.RetroArchImport
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolderRules
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.storage.TreeSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where Import from RetroArch is (first-run screens 5-7). */
sealed interface ImportStep {
    /** "Copy your saves from RetroArch?" */
    data object Offer : ImportStep
    data object Looking : ImportStep
    /** "Found N saves and N states" in [place] › [folders]; [subs]: the folders inside that were read ("saves · states"). */
    data class Found(
        val tree: Uri,
        val place: String,
        val folders: List<String>,
        val subs: String?,
        val found: RaFound,
        val plan: RaImportPlan,
    ) : ImportStep {
        val total get() = found.saves.size + found.states.size
    }
    data class Copying(val saves: Int, val states: Int) : ImportStep
    /** [saves], [states]: RetroArch's files now in Joey64 (copied, or the same file already there). */
    data class Done(val saves: Int, val states: Int, val failed: Int) : ImportStep
}

/**
 * Import from RetroArch (plan, Phase 1; approved 2026-11-05). Copies only: RetroArch's folder is
 * read through [RetroArchImport], which can't write there. The copies go into Joey64's data folder
 * (its own storage until a data folder is picked), where the emulator reads its saves.
 */
class ImportModel(app: Application) : AndroidViewModel(app) {
    private val _step = MutableStateFlow<ImportStep>(ImportStep.Offer)
    val step: StateFlow<ImportStep> = _step

    /** The picker opens at RetroArch's folder on internal storage, where RetroArch keeps it by default. */
    fun pickerUri(): Uri = DocumentsContract.buildDocumentUri(RomFolders.EXTERNAL_STORAGE, RomFolderRules.PRIMARY + ":RetroArch")

    fun look(tree: Uri, dataFolder: String?) {
        _step.value = ImportStep.Looking
        viewModelScope.launch {
            val app = getApplication<Application>()
            _step.value = withContext(Dispatchers.IO) {
                val name = FolderNames.of(app, tree)
                try {
                    val ra = TreeSpace(app.contentResolver, tree)
                    val top = ra.list(emptyList()).filter { it.isDir }.map { it.name }
                    val subs = listOf("saves", "states").filter { it in top }.joinToString(" · ").ifEmpty { null }
                    val found = RetroArchImport.find(ra)
                    val plan = RetroArchImport.plan(ra, found, DataFolder.of(app, dataFolder).space)
                    Log.i(TAG, "found ${found.saves.size} saves, ${found.states.size} states; ${plan.toCopy.size} new, " +
                        "${plan.alreadyThere.size} already there, ${plan.clashes.size} clashes, ${found.olderCopies.size} older copies, " +
                        "${found.otherFormatSaves.size} other-format saves")
                    ImportStep.Found(tree, name.place, name.folders, subs, found, plan)
                } catch (e: Exception) {
                    Log.w(TAG, "can't read $tree", e)
                    ImportStep.Found(tree, name.place, name.folders, null, RaFound(emptyList(), emptyList(), emptyList(), emptyList()),
                        RaImportPlan(emptyList(), emptyList(), emptyList()))
                }
            }
        }
    }

    /** Back from the Found screen to the offer. */
    fun back() {
        if (_step.value is ImportStep.Found) _step.value = ImportStep.Offer
    }

    /** Copies what [f] found. [useTheirs]: the clashes the user answered "Use RetroArch's"; the other clashes keep Joey64's file. */
    fun copy(f: ImportStep.Found, useTheirs: Set<RaFile>, dataFolder: String?) {
        if (_step.value != f) return
        val replace = f.plan.clashes.map { it.file }.filter { it in useTheirs }
        _step.value = ImportStep.Copying(f.found.saves.size, f.found.states.size)
        viewModelScope.launch {
            val app = getApplication<Application>()
            _step.value = withContext(Dispatchers.IO) {
                val ra = TreeSpace(app.contentResolver, f.tree)
                val joey = DataFolder.of(app, dataFolder).space
                var saves = 0
                var states = 0
                var failed = 0
                fun run(files: List<RaFile>, replace: Boolean) {
                    for ((kind, group) in files.groupBy { it.kind }) {
                        val r = RetroArchImport.copy(ra, joey, group, replace)
                        if (kind == DataKind.Saves) saves += r.copied else states += r.copied
                        failed += r.failed.size
                        r.failed.forEach { Log.w(TAG, "not copied: $it") }
                    }
                }
                run(f.plan.toCopy, replace = false)
                run(replace, replace = true)
                saves += f.plan.alreadyThere.count { it.kind == DataKind.Saves }
                states += f.plan.alreadyThere.count { it.kind == DataKind.States }
                Log.i(TAG, "copied: $saves saves, $states states in Joey64, $failed failed, " +
                    "${replace.size} replaced, ${f.plan.clashes.size - replace.size} kept")
                ImportStep.Done(saves, states, failed)
            }
        }
    }

    private companion object {
        const val TAG = "Joey64Import"
    }
}
