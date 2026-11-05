#pragma once
#include <string>

// Item 31 (2026-11-05): the device's thermal state, for the stats line and a fast-forward governor.
// Android's AThermal API (libandroid, looked up at run time: status needs API 30, headroom API 31).
// Status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown (-1 unknown).
// Headroom: the platform's forecast of thermal load in 10 s, 1.0 = where it starts severe throttling (NaN if unknown).
//
// Governor (dev, N64_THERMAL_GOV=1, off by default): fast-forward is the only extra load the app adds by itself
// (upscaling is the user's choice), so the governor lowers the fast-forward speed as the device heats up:
// 4x below headroom 0.8, 3x from 0.8, 2x from 0.9 or at moderate status and above; it steps back up one level
// only when headroom has fallen below 0.7 (and status below moderate), so it doesn't flap. Checked every 5 s.
namespace Thermal {
// Reads status and headroom (at most once a second, the platform's limit) and updates the governor.
void update();
// ", thermal <status>, headroom <h>[, ff <n>x]" for the stats line; "" when the API is missing.
std::string text();
// Fast-forward speed to use now: 4 unless the governor has lowered it.
int fastForwardSpeed();
}
