package com.joeysretro.joey64.controls

/**
 * What the Controls page edits (approved mockups 1-11): All games, or one game's own controls.
 * Pure, over a [ControlsStore], so the rules are tested on the JVM.
 *
 *  - A game's page shows its own controls, or All games' until the first change, which saves a whole
 *    copy as the game's own (mockups 9 and 11).
 *  - Which controller a player has is always All games' (the game page's Controller row changes it there).
 *  - Reset to default ([resetRow]) on All games is what Auto map gives that player's controller; on a
 *    game it's Use All games, the All games value.
 *
 * [defaultsFor] gives Auto map's setup for a player's controller (the Android side reads the device).
 */
class ControlsEditor(
    private val store: ControlsStore,
    /** The game's save name, or null for All games. */
    val game: String?,
    private val defaultsFor: (PlayerSetup) -> PlayerSetup,
) {
    var all: ControlsSet = store.all(); private set
    var own: ControlsSet? = game?.let { store.game(it) }; private set
    var hotkeys: Hotkeys = store.hotkeys(); private set

    /** What the page shows: the game's own (or All games') with All games' controllers. */
    val shown: ControlsSet get() {
        val g = own ?: return all
        return ControlsSet(List(4) { i -> g.players[i].copy(device = all.players[i].device) })
    }

    /** The rows of [player] that differ from All games (yellow dots); empty on All games. */
    fun changed(player: Int): Set<String> = if (game == null || own == null) emptySet()
        else shown.players[player].differences(all.players[player])

    /** How many rows a game's own controls change, over all players ("3 changes"). */
    fun changeCount(saveName: String): Int {
        val g = store.game(saveName) ?: return 0
        return (0..3).sumOf { g.players[it].copy(device = all.players[it].device).differences(all.players[it]).size }
    }

    /**
     * Saves [setup]'s buttons, stick, dead zone and pak for [player]: to All games, or the game's own
     * (made from All games on its first change). The controller is [setDevice]'s.
     */
    fun set(player: Int, setup: PlayerSetup) {
        if (game == null) {
            all = all.with(player, setup.copy(device = all.players[player].device)); store.saveAll(all)
        } else {
            val base = own ?: all
            val o = base.with(player, setup.copy(device = all.players[player].device))
            own = o; store.saveGame(game, o)
        }
    }

    /**
     * Gives [player] the controller [choice] with [setup] (Auto map's for a controller first picked);
     * a player who had it gets [player]'s old one, with the setups swapped (mockup 2). Always All games'.
     */
    fun setDevice(player: Int, choice: DeviceChoice, setup: PlayerSetup? = null) {
        val other = if (choice == DeviceChoice.None) -1 else all.players.indexOfFirst { it.device == choice }
        var a = all
        if (other >= 0 && other != player) {
            val mine = a.players[player]
            a = a.with(player, a.players[other]).with(other, mine)
        } else {
            a = a.with(player, (setup ?: a.players[player]).copy(device = choice))
        }
        all = a; store.saveAll(a)
    }

    /** The value [row] has by default: Auto map's on All games, All games' on a game. */
    private fun baseFor(player: Int): PlayerSetup =
        if (game != null) all.players[player] else defaultsFor(all.players[player])

    /** Y: Reset to default (All games) / Use All games (a game) for one row: an N64 button's name, "Stick", "DeadZone", "Pak", "StickFeel" or one of [StickFeel.ROWS]. */
    fun resetRow(player: Int, row: String) {
        val cur = shown.players[player]
        val base = baseFor(player)
        val next = when (row) {
            "Stick" -> cur.copy(stick = base.stick)
            "DeadZone" -> cur.copy(deadZone = base.deadZone)
            "Pak" -> cur.copy(pak = base.pak, gbGame = base.gbGame, gbSave = base.gbSave)
            "RumbleOn" -> cur.copy(rumbleOn = base.rumbleOn)
            "RumbleStrength" -> cur.copy(rumbleStrength = base.rumbleStrength)
            "Tap" -> cur.copy(latch = base.latch)
            "Macros" -> cur.copy(macros = base.macros)
            "Turbo" -> cur.copy(turbo = base.turbo, turboSpeed = base.turboSpeed)
            "CHold" -> cur.copy(cHold = base.cHold)
            // The page's Stick feel row: all of it; a row inside its popup: that one.
            "StickFeel" -> cur.copy(feel = base.feel)
            in StickFeel.ROWS -> cur.copy(feel = cur.feel.withRow(row, base.feel))
            else -> N64Button.entries.firstOrNull { it.name == row }?.let { cur.with(it, base.buttons[it]) } ?: return
        }
        set(player, next)
    }

    /** Resets a whole group of buttons (the D-pad or C buttons row). */
    fun resetGroup(player: Int, group: List<N64Button>) {
        val base = baseFor(player)
        var s = shown.players[player]
        for (b in group) s = s.with(b, base.buttons[b])
        if (group == N64Button.C) s = s.copy(cHold = base.cHold)
        set(player, s)
    }

    /** Puts every row of [player]'s buttons on what Auto map gives (the Auto map button), stick/dead zone/pak kept. */
    fun autoMap(player: Int, mapped: PlayerSetup) {
        val cur = shown.players[player]
        // A stickless pad's map has the stick on the D-pad and Hold for C buttons (approved stickless mockup 2026-11-13).
        set(player, cur.copy(buttons = mapped.buttons, cHold = mapped.cHold, stick = if (mapped.cHold != null) mapped.stick else cur.stick))
    }

    /**
     * Start: Reset tab for [player] (approved mockup B, 2026-11-07): on All games every row goes back to what Auto
     * map gives the player's controller; on a game the player uses All games' again (the game's file goes once no
     * player differs). The controller stays. Hotkeys aren't a player's.
     */
    fun resetPlayer(player: Int) {
        // Tap to hold and macros stay: the approved popup names the rows it resets, and they aren't among them.
        if (game == null) { val cur = all.players[player]; set(player, defaultsFor(cur).copy(latch = cur.latch, macros = cur.macros, turbo = cur.turbo, turboSpeed = cur.turboSpeed)); return }
        val o = own ?: return
        val next = o.with(player, all.players[player])
        if ((0..3).all { next.players[it].copy(device = all.players[it].device).differences(all.players[it]).isEmpty() }) removeGame(game)
        else { own = next; store.saveGame(game, next) }
    }

    /** X on the game chooser: the game goes back to All games. */
    fun removeGame(saveName: String) {
        store.removeGame(saveName)
        if (saveName == game) own = null
    }

    fun gamesWithOwn(): List<String> = store.gamesWithOwn()

    /**
     * Loads [profile] into [player] (Controls extras' Profiles): to All games, or with a game in the
     * chip that game's own only. The controller and pak stay. Not linked afterwards.
     */
    fun loadProfile(player: Int, profile: Profile) = set(player, Profile.applyTo(shown.players[player], profile))

    fun profiles(): List<Profile> = store.profiles()

    /** Saves what [player] plays with now (on this page: All games or the game's) as [name], replacing one of that name. */
    fun saveProfile(player: Int, name: String, deviceName: String, now: Long) =
        store.saveProfile(Profile(name.trim(), now, deviceName, shown.players[player]))

    fun renameProfile(old: String, new: String) = store.renameProfile(old, new.trim())

    fun removeProfile(name: String) = store.removeProfile(name)

    fun setHotkeys(h: Hotkeys) { hotkeys = h; store.saveHotkeys(h) }
}
