package com.joeysretro.joey64.video

/**
 * Games that draw junk into the border a TV hid, so Crop edges gets a default for them: used only while neither
 * All games nor the game sets Crop edges, so any choice the player makes wins (Off included). By the header's game
 * code without the region letter, so every region, revision and hack of the game gets it.
 *
 * Ocarina of Time (Joey 2026-11-12: "Yes, OoT only"): a small teal mark at the top left in dungeons, drawn by the game
 * into its own frame (both renderers, every VI filter; README Full-run fixes (f1)). Normal (8 lines) hides it, Thin
 * leaves a sliver. CZL is the USA and Japan code, NZL Europe's and LodgeNet's.
 */
object GameCrop {
    private val GAMES = mapOf("CZL" to 8, "NZL" to 8)

    /** The lines [gameCode] ("CZLE" or "CZL") crops by default, or null when it has none or [v] sets Crop edges. */
    fun lines(gameCode: String?, v: VideoSettings): Int? =
        if (VideoKey.Crop in v.values) null else gameCode?.takeIf { it.length >= 3 }?.let { GAMES[it.take(3).uppercase()] }

    /** The lines to crop: [v]'s Crop edges, or [gameCode]'s default when [v] doesn't set it. */
    fun of(gameCode: String?, v: VideoSettings): Int = lines(gameCode, v) ?: v.int(VideoKey.Crop)

    /** The Crop edges row's value for [gameCode] (Joey 2026-11-12: "Yes, show the default"): "Normal (this game's
     *  default)" while the game's default applies, else the setting's own label. */
    fun label(gameCode: String?, v: VideoSettings): String =
        lines(gameCode, v)?.let { "${VideoChoices.label(VideoKey.Crop, it.toString())} (this game's default)" }
            ?: VideoChoices.label(VideoKey.Crop, v[VideoKey.Crop])

    /** The game's header code for [saveName] from the library, off the main thread; null when it isn't there. */
    fun codeFor(context: android.content.Context, saveName: String): String? = try {
        com.joeysretro.joey64.launcher.data.LibraryDb.get(context).gameWithFile(saveName, com.joeysretro.joey64.states.GameStates::saveNameOf)?.headerId
    } catch (e: Exception) { com.joeysretro.joey64.JLog.e("Joey64Video", "can't look up $saveName: $e"); null }
}
