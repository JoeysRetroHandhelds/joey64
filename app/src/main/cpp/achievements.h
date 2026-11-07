// RetroAchievements (plan Phase 7): rcheevos rc_client in the native host. Softcore only until
// RetroAchievements approves Joey64. The Kotlin side is com.joeysretro.joey64.achievements.RaBridge:
// it does the HTTP calls (rc_client hands it each request) and gets events as JSON.
#pragma once
#include <cstddef>
#include <cstdint>

namespace Cheevos {
// After retro_load_game: the ROM as loaded (any byte order) for the RetroAchievements hash, and the
// core's RDRAM for the memory reads. Starts identifying the game if the user is signed in (or a
// sign-in is under way; rc_client waits for it).
void gameLoaded(const uint8_t* rom, size_t romSize, uint8_t* ram, size_t ramSize);
// When the game stops.
void gameUnloaded();
// On the emulation thread: after each retro_run, and about once a second while paused.
void doFrame();
void idle();
// After the core's retro_reset (the pause menu's Reset).
void reset();
// After a save state loads: achievements start over from it.
void stateLoaded();
// The RetroAchievements hash of the last game loaded (32 hex digits), for logs and tests.
const char* lastHash();
}
