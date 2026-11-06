package com.joeysretro.joey64.launcher.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The launcher's settings as one value; screens read it as a Flow and recompose on change. */
data class LauncherSettings(
    val theme: ThemeChoice = ThemeChoice.Dark,
    /** Tree URIs of the picked ROM folders. */
    val romFolders: Set<String> = emptySet(),
    /** Tree URI of the picked data folder; null = Joey64's own storage. */
    val dataFolder: String? = null,
    /** Decision 3: both on by default (Joey, 2026-11-05). */
    val downloadBoxArt: Boolean = true,
    val downloadCheats: Boolean = true,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

/**
 * Settings in DataStore (plan: built the way JoeyOS is built). One DataStore per file per
 * process, so everything goes through this.
 */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<LauncherSettings> = store.data.map { p ->
        LauncherSettings(
            theme = p[THEME]?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } } ?: ThemeChoice.Dark,
            romFolders = p[ROM_FOLDERS] ?: emptySet(),
            dataFolder = p[DATA_FOLDER],
            downloadBoxArt = p[BOX_ART] ?: true,
            downloadCheats = p[CHEATS] ?: true,
        )
    }

    suspend fun setTheme(choice: ThemeChoice) = store.edit { it[THEME] = choice.name }
    suspend fun addRomFolder(treeUri: String) = store.edit { it[ROM_FOLDERS] = (it[ROM_FOLDERS] ?: emptySet()) + treeUri }
    suspend fun removeRomFolder(treeUri: String) = store.edit { it[ROM_FOLDERS] = (it[ROM_FOLDERS] ?: emptySet()) - treeUri }
    suspend fun setDataFolder(treeUri: String?) = store.edit { if (treeUri == null) it.remove(DATA_FOLDER) else it[DATA_FOLDER] = treeUri }
    suspend fun setDownloadBoxArt(on: Boolean) = store.edit { it[BOX_ART] = on }
    suspend fun setDownloadCheats(on: Boolean) = store.edit { it[CHEATS] = on }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val ROM_FOLDERS = stringSetPreferencesKey("rom_folders")
        val DATA_FOLDER = stringPreferencesKey("data_folder")
        val BOX_ART = booleanPreferencesKey("download_box_art")
        val CHEATS = booleanPreferencesKey("download_cheats")
    }
}
