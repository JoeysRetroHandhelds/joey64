package com.joeysretro.joey64.netplay

import com.joeysretro.joey64.controls.HotkeyAction

/**
 * What the game screen reads from the netplay room four times a second ([com.joeysretro.joey64.NativeBridge.netplayInfo]),
 * for the in-game netplay screens (approved mockup 2026-11-10, scratchpad build_netplay3.py / netplay3_mock.png,
 * "Approve as drawn"; room lost: OK only).
 */
data class NetInfo(
    val inRoom: Boolean,
    val hosting: Boolean,
    /** Players in the room, bit 0 = player 1 (the host only knows it). */
    val joined: Int,
    /** The games no longer match (the room's CP0 check). */
    val outOfStep: Boolean,
    /** Players who left, bit 0 = player 1. */
    val left: Int,
    /** The player (1-4) whose pause menu has the game stopped, 0 for none. */
    val pausedBy: Int,
    /** The player (1-4) the game is waiting for (their input is late), 0 for none. */
    val waitingFor: Int,
    /** This game's own player (1-4), 0 when the bridge doesn't say (an older one). */
    val me: Int = 0,
) {
    companion object {
        val OFF = NetInfo(false, false, 0, false, 0, 0, 0, 0)

        fun of(v: IntArray?): NetInfo {
            if (v == null || v.size < 4 || v[0] == 0) return OFF
            return NetInfo(true, v[1] != 0, v[2], v[3] and 1 != 0, (v[3] shr 1) and 0xF,
                v.getOrElse(4) { 0 }, v.getOrElse(5) { 0 }, v.getOrElse(6) { 0 })
        }
    }

    /** Players 1-4 in [mask] (bit 0 = player 1). */
    fun playersIn(mask: Int) = (1..4).filter { mask and (1 shl (it - 1)) != 0 }
}

object NetplayWords {
    /** "Player 2", "Players 1 and 3", "Players 1, 2 and 4". */
    fun players(list: List<Int>): String = when (list.size) {
        0 -> ""
        1 -> "Player ${list[0]}"
        else -> "Players " + list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    /** Screen 2: the line under the pauser's menu title. [others]: the other players, when known (the host knows them). */
    fun waitingForYou(others: List<Int>): String = when (others.size) {
        0 -> "The other players are waiting for you"
        1 -> "Player ${others[0]} is waiting for you"
        else -> players(others) + " are waiting for you"
    }

    /** Screen 1: "Player 2 paused the game". */
    fun pausedTitle(player: Int) = "Player $player paused the game"
    /** Screen 1's text after the player's name ("**Sam (Odin 3)** opened their menu. ..."); "They" before the room's list arrives. */
    const val PAUSED_TEXT = " opened their menu. The game carries on when they're back."

    /** Screen 3: "Waiting for Player 3" / "Player 3's connection is slow". */
    fun waitingTitle(player: Int) = "Waiting for Player $player"
    fun slowLine(player: Int, name: String? = null) = "${name ?: "Player $player"}'s connection is slow"

    /** Screen 6: the pop-up and greyed rows. */
    const val BLOCKED = "Not in a netplay room"

    /**
     * What a hotkey that does nothing in a room says (screen 6: "every player has to stay in step"), or null for one that
     * works in a room. Pause works: it pauses everyone, as the pause menu does.
     */
    fun blockedLine(action: HotkeyAction): String? = when (action) {
        HotkeyAction.Save, HotkeyAction.Load, HotkeyAction.UndoLoad, HotkeyAction.UndoSave -> "Save states wait until you leave"
        HotkeyAction.Reset -> "Reset waits until you leave"
        HotkeyAction.FastForward -> "Fast-forward waits until you leave"
        HotkeyAction.SlowMotion -> "Slow motion waits until you leave"
        HotkeyAction.Rewind -> "Rewind waits until you leave"
        HotkeyAction.FrameAdvance -> "Frame advance waits until you leave"
        HotkeyAction.GameShark -> "The GameShark button waits until you leave"
        else -> null
    }

    /** Screen 5. */
    const val OUT_OF_STEP_TITLE = "The games are out of step"
    const val OUT_OF_STEP_TEXT = "Your game no longer matches the other players'. You can keep playing, but what you see may not be what they see."

    /** The pause menu's Quit row in a room. */
    const val LEAVE = "Leave the room"
    /** The host's Out of step choice in place of Leave the room (the room closes with the host). */
    const val END_FOR_EVERYONE = "End for everyone"

    /** Player-left popup (approved 2026-11-05, unchanged in v2) without its Wait row: rejoin isn't built (Joey 2026-11-10: after the first testing build). */
    fun leftTitle(player: Int, name: String? = null) = "${name ?: "Player $player"} left the game"
    fun leftText(player: Int) = "The game is paused for everyone. You can carry on without Player $player."

    /**
     * This device's player in the room: 1 when it hosts (N64_NETPLAY_SERVE joins as player 1), else the third field of
     * N64_NETPLAY ("host:port:player"); 0 when it can't tell.
     */
    fun myPlayer(hosting: Boolean, joinEnv: String?, fromBridge: Int = 0): Int = if (fromBridge in 1..4) fromBridge else
        if (hosting) 1 else joinEnv?.split(':')?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 1..4 } ?: 0
}
