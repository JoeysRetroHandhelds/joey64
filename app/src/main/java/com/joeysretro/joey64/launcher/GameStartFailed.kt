package com.joeysretro.joey64.launcher

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A game the launcher started that could not start (code audit 2, finding 2): a 64DD disk whose region has no IPL BIOS
 * (only IPL_DEV.n64 there and a Japanese disk) made the core shut down and the game screen close. The library, in the
 * same process, asks for the BIOS again with the existing popup ([LibraryModel.needsBios]). A start from another app
 * just closes.
 */
object GameStartFailed {
    /**
     * What the disk needed: its region's IPL BIOS, or its cartridge (EXTRA_DD_CART didn't unpack, code audit 2 finding 4);
     * or [Rom]: any other game that didn't start, which the library says with "<game> won't start" (approved problem
     * screens 2026-11-08, screens 1-2) and [why].
     */
    enum class Need { Bios, Cart, Rom,
        /** Netplay (approved screens): the room was lost mid-game, couldn't be reached, or has another copy of the game. */
        NetLost, NetUnreachable, NetMismatch }

    /** Why a game didn't start, for its Details' What happened line. */
    enum class Why(val text: String) {
        Unreadable("Joey64 couldn't read an N64 game in the file"),
        Refused("The emulator couldn't load it"),
        Stopped("The emulator stopped while loading it"),
    }
    class Failed(val need: Need, val intent: Intent, val why: Why? = null)

    private val _failed = MutableStateFlow<Failed?>(null)
    /** The disk start that failed, until the library takes it. */
    val failed: StateFlow<Failed?> = _failed

    fun diskNeedsBios(intent: Intent) { _failed.value = Failed(Need.Bios, intent) }
    fun diskNeedsCart(intent: Intent) { _failed.value = Failed(Need.Cart, intent) }
    fun romFailed(intent: Intent, why: Why) { _failed.value = Failed(Need.Rom, intent, why) }
    /** A netplay game that ended or didn't start: [code] is [com.joeysretro.joey64.NativeBridge.coreShutDown]'s 3, 4 or 6. */
    fun netEnded(intent: Intent, code: Int) {
        _failed.value = Failed(when (code) { 3 -> Need.NetLost; 4 -> Need.NetUnreachable; else -> Need.NetMismatch }, intent)
    }

    /** The library took it. */
    fun take() { _failed.value = null }
}
