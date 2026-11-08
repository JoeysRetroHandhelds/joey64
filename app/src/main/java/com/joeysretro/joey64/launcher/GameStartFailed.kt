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
    /** What the disk needed: its region's IPL BIOS, or its cartridge (EXTRA_DD_CART didn't unpack, code audit 2 finding 4). */
    enum class Need { Bios, Cart }
    class Failed(val need: Need, val intent: Intent)

    private val _failed = MutableStateFlow<Failed?>(null)
    /** The disk start that failed, until the library takes it. */
    val failed: StateFlow<Failed?> = _failed

    fun diskNeedsBios(intent: Intent) { _failed.value = Failed(Need.Bios, intent) }
    fun diskNeedsCart(intent: Intent) { _failed.value = Failed(Need.Cart, intent) }

    /** The library took it. */
    fun take() { _failed.value = null }
}
