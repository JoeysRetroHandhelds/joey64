package com.joeysretro.joey64.launcher.storage

import android.content.Context
import androidx.core.net.toUri
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Moves Joey64's data (saves, states, screenshots, cheats, save backups, the settings' copy) to a
 * picked folder, or back to Joey64's own storage, and switches to it. Settings › Saves › Data folder
 * and first run's "Where should Joey64 keep saves, states and screenshots?" both use it.
 */
object DataFolderMove {
    private const val TAG = "Joey64DataFolder"

    /** What a move came to. */
    sealed interface Outcome {
        /** Moved and switched; [result] counts the files. */
        data class Moved(val result: MoveResult) : Outcome
        /**
         * Switched without moving anything: the folder in use couldn't be read (its card out, its permission taken away in
         * Android's settings; code audit 3, M4: it "moved" 0 files and gave the folder's permission up). Its files stay there
         * and so does the permission, so picking it again later moves them over. The newest saves come along anyway: the
         * core's working copies go to the new folder at each game's next start.
         */
        data object OldUnreadable : Outcome
        /** Not switched: the picked folder can't be written, overlaps the one in use, or the move failed. */
        data object Failed : Outcome
    }

    /** A move under way, or the last one's outcome until the screen showing it takes it ([take]). */
    sealed interface State {
        data object Idle : State
        data class Moving(val tree: String?) : State
        data class Done(val tree: String?, val outcome: Outcome) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Not a screen's scope (code audit 3, M5: leaving Settings › Saves mid-move cancelled it half done, the switch half made). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts a move to [tree] ([to]); false when one is already under way. Watch [state] for the outcome. */
    fun start(context: Context, tree: String?): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            if (_state.value is State.Moving) return false
            _state.value = State.Moving(tree)
        }
        scope.launch { _state.value = State.Done(tree, to(app, tree)) }
        return true
    }

    /** The finished move's outcome, once (the screen that shows it takes it); null while none is waiting. */
    fun take(): State.Done? = synchronized(this) {
        (_state.value as? State.Done)?.also { _state.value = State.Idle }
    }

    /** [tree] null: back to Joey64's own storage. A file that couldn't move stays where it was (the result counts it). */
    suspend fun to(context: Context, tree: String?): Outcome = withContext(Dispatchers.IO) {
        try {
            val target = if (tree == null) DataFolder.internal(context) else {
                RomFolders.keep(context, tree.toUri(), write = true)
                DataFolder.picked(context, tree.toUri())
            }
            val from = DataFolders.current(context)
            if (!from.canMoveTo(target)) return@withContext Outcome.Failed
            // Before anything moves: a folder Joey64 can't write would take every file and keep none.
            if (!canWrite(target.space)) {
                if (tree != null) RomFolders.releaseIfUnused(context, tree)
                return@withContext Outcome.Failed
            }
            val old = DataFolders.treeUri(context)
            val oldReadable = from.isInternal || (from.space as? TreeSpace)?.readable() == true
            val res = if (!oldReadable) null else {
                SrmSync.pushAll(context)
                DataMover.settleSaves(from.space, target.space, DataMover.move(from.space, target.space, DataFolders.KINDS), fromIsWorkingCopy = from.isInternal)
            }
            DataFolderRules.hideFromGallery(target.space, DataFolders.KINDS)
            DataFolders.set(context, tree)
            SettingsStore(context).setDataFolder(tree)
            // The settings' copy follows (Joey 2026-11-07: "Move settings too").
            com.joeysretro.joey64.saves.SettingsMirror.moved(context, old)
            // A folder that couldn't be read keeps its permission: its files are still there.
            if (oldReadable && old != null && old != tree) RomFolders.releaseIfUnused(context, old)
            if (res == null) {
                JLog.e(TAG, "data folder: switched without moving, the old folder can't be read")
                Outcome.OldUnreadable
            } else {
                JLog.i(TAG, "data folder: moved ${res.moved}, already there ${res.alreadyThere}, saves kept aside ${res.keptAside}, " +
                    "left ${res.conflicts.size}, failed ${res.failed.size}")
                Outcome.Moved(res)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JLog.e(TAG, "data folder: move failed: $e"); Outcome.Failed
        }
    }

    /** A test file written and removed. */
    private fun canWrite(space: FileSpace): Boolean = try {
        val probe = listOf(".joey64-write-check")
        space.openWrite(probe).use { it.write(0) }
        space.delete(probe)
        true
    } catch (e: Exception) {
        JLog.e(TAG, "data folder: can't write the picked folder: $e"); false
    }
}
