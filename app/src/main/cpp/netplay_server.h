// Netplay room server (2026-11-10): the mupen64plus netplay protocol (the core's src/main/netplay.c is the
// client), run in the host's own process on the Wi-Fi host's device, so a room needs no outside server.
// TCP: player registration, player 1's settings and saves to the others, disconnect notices.
// UDP: each player's input by event count, relayed to everyone with `delay` frames of input delay;
// the CP0 desync check every 600 VIs. GPL-3.0-or-later.
#pragma once
#include <cstdint>

namespace NetplayServer {
// Starts the room on `port` (TCP and UDP) for `players` players (1-4) with `delay` frames of input delay.
// Returns false if the port can't be opened.
bool start(int port, int players, int delay);
void stop();
bool running();
// Room state for the UI: players registered so far (bit per player), status byte (bit 0 desync,
// bits 1-4 a player left), and the highest input count seen.
uint8_t registeredMask();
uint8_t status();
}
