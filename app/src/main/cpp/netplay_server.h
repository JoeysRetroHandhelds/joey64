// Netplay room server (2026-11-10): the mupen64plus netplay protocol (the core's src/main/netplay.c is the
// client), run in the host's own process on the Wi-Fi host's device, so a room needs no outside server.
// TCP: player registration, player 1's settings and saves to the others, disconnect notices.
// UDP: each player's input by event count, relayed to everyone with `delay` frames of input delay;
// the CP0 desync check every 600 VIs. GPL-3.0-or-later.
#pragma once
#include <cstdint>

namespace NetplayServer {
// Starts the room on `port` (TCP and UDP) for `players` players (1-4) with `delay` frames of input delay.
// delay -1 = adaptive: starts at 2 frames and moves between 2 and 12 with how long the players wait for input.
// Returns false if the port can't be opened.
bool start(int port, int players, int delay);
void stop();
bool running();
// Room state for the UI: players registered so far (bit per player), status byte (bit 0 desync,
// bits 1-4 a player left, bits 5-7 the paused player 1-4 or 0).
uint8_t registeredMask();
uint8_t status();
// The room's input delay now, in frames (0 = no room).
int inputDelay();
}
