// UPnP port mapping for a netplay room (2026-11-10, Joey: "Yes, add UPnP"). GPL-3.0-or-later.
#pragma once
#include <string>

namespace Upnp {
// What the router said, for the UI and the session log.
enum State : int {
    Off = 0,         // not asked (no room, or N64_NETPLAY_UPNP=0)
    Looking = 1,     // looking for the router
    Open = 2,        // the port is open on the router: outside() is the address others use
    NoRouter = 3,    // neither a UPnP nor a NAT-PMP router answered (both off on the router, or none)
    Refused = 4,     // the router answered but would not open the port
    DoubleNat = 5,   // opened, but the router's own outside address is private (a second router or the carrier's
                     // NAT in front of it): players outside still can't reach the room
    AllTaken = 6,    // every outside port a room code can hold (45000-45007) is held by other rooms on this network
};
// Opens `port` (TCP and UDP) to this device on the router, on its own thread, on the first free outside port of
// 45000-45007 (the room's own first). Leased for 2 minutes and renewed every minute, so a room that crashed frees its
// outside port within about 2 minutes (a UPnP router that only takes permanent mappings gets one). Asked again for the
// port already open, it keeps that mapping.
// NAT-PMP first (2026-11-10; ~60 ms against UPnP's ~4.4 s), then UPnP. NAT-PMP goes to the router in N64_NETPLAY_GW (the app sets it
// from the network's default route; Android's main route table has none, so libnatpmp can't find it itself).
// Dev: N64_NETPLAY_UPNP=0 skips both, =natpmp only NAT-PMP, =upnp only UPnP.
void open(int port);
// Removes the mappings. Returns at once: opens and closes run in order on Upnp's own thread, which waits for the router
// (up to ~2 s).
void close();
State state();
std::string outside(); // "ip:port" once Open or DoubleNat, else ""
}
