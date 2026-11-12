package com.joeysretro.joey64.launcher.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.longPreferencesKey
import com.joeysretro.joey64.launcher.library.BetaMode
import com.joeysretro.joey64.launcher.library.CoverSize
import com.joeysretro.joey64.launcher.library.LookFor
import com.joeysretro.joey64.launcher.library.NameStyle
import com.joeysretro.joey64.launcher.library.NamesMode
import com.joeysretro.joey64.launcher.library.RegionGroup
import com.joeysretro.joey64.launcher.library.SortBy
import com.joeysretro.joey64.launcher.theme.PopupTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The launcher's settings as one value; screens read it as a Flow and recompose on change. */
data class LauncherSettings(
    val theme: ThemeChoice = ThemeChoice.Dark,
    /** Settings › Appearance › Pop-ups: achievement, leaderboard and cheat pop-ups during play. */
    val popupTheme: PopupTheme = PopupTheme.Dark,
    /** Tree URIs of the picked ROM folders. */
    val romFolders: Set<String> = emptySet(),
    /** Tree URIs of the Game Boy folders, read only (Transfer Pak; approved Game Boy folder mockup 2026-11-08). */
    val gbFolders: Set<String> = emptySet(),
    /** Tree URI of the picked data folder; null = Joey64's own storage. */
    val dataFolder: String? = null,
    /** Decision 3: both on by default (Joey, 2026-11-05). */
    val downloadBoxArt: Boolean = true,
    val downloadCheats: Boolean = true,
    /** Box art quality (Joey 2026-11-07): High = covers 640 px wide (the default), Low = 320. */
    val boxArtLow: Boolean = false,
    /** First run is finished (ROMs folder picked, Import from RetroArch offered); until then the app opens to it. */
    val setupDone: Boolean = false,
    /** First run's "Where should Joey64 keep saves, states and screenshots?" was answered (screens 3-4, built 2026-11-07). */
    val dataAsked: Boolean = false,
    /** First run's Import from RetroArch was answered; Sign in to RetroAchievements comes next (approved D3, 2026-11-11). */
    val importAsked: Boolean = false,
    // Settings › Library (approved mockup 2026-11-08): Show, Covers, Look for new games.
    // Recently played by default (Joey 2026-11-11; it was Name). A sort someone picked is kept.
    val sortBy: SortBy = SortBy.RecentlyPlayed,
    val favouritesFirst: Boolean = false,
    val regions: Set<RegionGroup> = RegionGroup.entries.toSet(),
    val oneVersion: Boolean = true,
    /** Regions › Betas and demos (approved mockup 1A, 2026-11-11), under One version per game. */
    val betas: BetaMode = BetaMode.WithGame,
    val coverSize: CoverSize = CoverSize.Medium,
    val names: NamesMode = NamesMode.DEFAULT,
    val nameStyle: NameStyle = NameStyle.Short,
    val lookFor: LookFor = LookFor.Start,
    /** Once a day: the epoch day the folders were last read. */
    val lastLookDay: Long? = null,
)

// A damaged file starts over from the defaults instead of throwing on every read (the launcher would close at each start;
// code audit 3, M10 found it for the sign-in file). The data folder's settings copy is untouched.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        com.joeysretro.joey64.JLog.e("Joey64Settings", "launcher settings were damaged: starting from the defaults ($it)")
        androidx.datastore.preferences.core.emptyPreferences()
    })

/**
 * Settings in DataStore (plan: built the way JoeyOS is built). One DataStore per file per
 * process, so everything goes through this.
 */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<LauncherSettings> = store.data.map { p ->
        LauncherSettings(
            theme = p[THEME]?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } } ?: ThemeChoice.Dark,
            popupTheme = p[POPUP_THEME]?.let { name -> PopupTheme.entries.firstOrNull { it.name == name } } ?: PopupTheme.Dark,
            romFolders = p[ROM_FOLDERS] ?: emptySet(),
            gbFolders = p[GB_FOLDERS] ?: emptySet(),
            dataFolder = p[DATA_FOLDER],
            downloadBoxArt = p[BOX_ART] ?: true,
            downloadCheats = p[CHEATS] ?: true,
            boxArtLow = p[BOX_ART_LOW] ?: false,
            setupDone = p[SETUP_DONE] ?: false,
            dataAsked = p[DATA_ASKED] ?: false,
            importAsked = p[IMPORT_ASKED] ?: false,
            sortBy = enumOr(p[SORT_BY], SortBy.RecentlyPlayed),
            favouritesFirst = p[FAV_FIRST] ?: false,
            regions = p[REGIONS]?.mapNotNull { n -> RegionGroup.entries.firstOrNull { it.name == n } }?.toSet()?.takeIf { it.isNotEmpty() }
                ?: RegionGroup.entries.toSet(),
            oneVersion = p[ONE_VERSION] ?: true,
            betas = enumOr(p[BETAS], BetaMode.WithGame),
            coverSize = enumOr(p[COVER_SIZE], CoverSize.Medium),
            names = enumOr(p[NAMES], NamesMode.DEFAULT),
            nameStyle = enumOr(p[NAME_STYLE], NameStyle.Short),
            lookFor = enumOr(p[LOOK_FOR], LookFor.Start),
            lastLookDay = p[LAST_LOOK_DAY],
        )
    }

    suspend fun setTheme(choice: ThemeChoice) = store.edit { it[THEME] = choice.name }
    suspend fun setPopupTheme(choice: PopupTheme) = store.edit { it[POPUP_THEME] = choice.name }
    suspend fun addRomFolder(treeUri: String) = store.edit { it[ROM_FOLDERS] = (it[ROM_FOLDERS] ?: emptySet()) + treeUri }
    suspend fun removeRomFolder(treeUri: String) = store.edit { it[ROM_FOLDERS] = (it[ROM_FOLDERS] ?: emptySet()) - treeUri }
    suspend fun addGbFolder(treeUri: String) = store.edit { it[GB_FOLDERS] = (it[GB_FOLDERS] ?: emptySet()) + treeUri }
    suspend fun removeGbFolder(treeUri: String) = store.edit { it[GB_FOLDERS] = (it[GB_FOLDERS] ?: emptySet()) - treeUri }
    suspend fun setDataFolder(treeUri: String?) = store.edit { if (treeUri == null) it.remove(DATA_FOLDER) else it[DATA_FOLDER] = treeUri }
    suspend fun setDownloadBoxArt(on: Boolean) = store.edit { it[BOX_ART] = on }
    suspend fun setDownloadCheats(on: Boolean) = store.edit { it[CHEATS] = on }
    suspend fun setBoxArtLow(on: Boolean) = store.edit { it[BOX_ART_LOW] = on }
    suspend fun setSetupDone() = store.edit { it[SETUP_DONE] = true }
    suspend fun setDataAsked() = store.edit { it[DATA_ASKED] = true }
    suspend fun setImportAsked() = store.edit { it[IMPORT_ASKED] = true }
    suspend fun setSortBy(v: SortBy) = store.edit { it[SORT_BY] = v.name }
    suspend fun setFavouritesFirst(on: Boolean) = store.edit { it[FAV_FIRST] = on }
    /** At least one stays ticked (the popup won't untick the last). */
    suspend fun setRegions(v: Set<RegionGroup>) = store.edit { if (v.isNotEmpty()) it[REGIONS] = v.map { r -> r.name }.toSet() }
    suspend fun setOneVersion(on: Boolean) = store.edit { it[ONE_VERSION] = on }
    suspend fun setBetas(v: BetaMode) = store.edit { it[BETAS] = v.name }
    suspend fun setCoverSize(v: CoverSize) = store.edit { it[COVER_SIZE] = v.name }
    suspend fun setNames(v: NamesMode) = store.edit { it[NAMES] = v.name }
    suspend fun setNameStyle(v: NameStyle) = store.edit { it[NAME_STYLE] = v.name }
    suspend fun setLookFor(v: LookFor) = store.edit { it[LOOK_FOR] = v.name }
    suspend fun setLastLookDay(day: Long) = store.edit { it[LAST_LOOK_DAY] = day }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val POPUP_THEME = stringPreferencesKey("popup_theme")
        val ROM_FOLDERS = stringSetPreferencesKey("rom_folders")
        val GB_FOLDERS = stringSetPreferencesKey("gb_folders")
        val DATA_FOLDER = stringPreferencesKey("data_folder")
        val BOX_ART = booleanPreferencesKey("download_box_art")
        val CHEATS = booleanPreferencesKey("download_cheats")
        val BOX_ART_LOW = booleanPreferencesKey("box_art_low")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val DATA_ASKED = booleanPreferencesKey("data_asked")
        val IMPORT_ASKED = booleanPreferencesKey("import_asked")
        val SORT_BY = stringPreferencesKey("library_sort")
        val FAV_FIRST = booleanPreferencesKey("library_favourites_first")
        val REGIONS = stringSetPreferencesKey("library_regions")
        val ONE_VERSION = booleanPreferencesKey("library_one_version")
        val BETAS = stringPreferencesKey("library_betas")
        val COVER_SIZE = stringPreferencesKey("library_cover_size")
        val NAMES = stringPreferencesKey("library_names")
        val NAME_STYLE = stringPreferencesKey("library_name_style")
        val LOOK_FOR = stringPreferencesKey("library_look_for")
        val LAST_LOOK_DAY = longPreferencesKey("library_last_look_day")

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
