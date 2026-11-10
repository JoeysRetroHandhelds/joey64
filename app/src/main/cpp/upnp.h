// UPnP port mapping for a netplay room (2026-11-10, Joey: "Yes, add UPnP"). GPL-3.0-or-later.
#pragma once
#include <string>

namespace Upnp {
// What the router said, for the UI and the session log.
enum State : int {
    Off = 0,         // not asked (no room, or N64_NETPLAY_UPNP=0)
    Looking = 1,     // looking for the router
    Open = 2,        // the port is open on the router: outside() is the address others use
    NoRouter = 3,    // no UPnP router answered (UPnP off on the router, or none)
    Refused = 4,     // the router answered but would not open the port
    DoubleNat = 5,   // opened, but the router's own outside address is private (a second router or the carrier's
                     // NAT in front of it): players outside still can't reach the room
};
// Opens `port` (TCP and UDP) to this device on the router, on its own thread; renewed every 30 minutes.
void open(int port);
// Removes the mappings (waits up to ~2 s for the router).
void close();
State state();
std::string outside(); // "ip:port" once Open or DoubleNat, else ""
}
