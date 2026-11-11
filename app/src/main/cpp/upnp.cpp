// UPnP port mapping for a netplay room: see upnp.h. GPL-3.0-or-later.
// miniupnpc 2.3.3 (BSD-3-Clause, third_party/miniupnpc), as M64Plus AE does for its rooms.
#include "upnp.h"
#include "joeylog.h"

#include "miniupnpc.h"
#include "upnpcommands.h"
#include "upnperrors.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#define ULOGI(...) joeyLog(ANDROID_LOG_INFO, "netplay", __VA_ARGS__)

namespace Upnp {
namespace {
std::thread g_thread;
std::mutex g_mutex;
std::condition_variable g_cv;
bool g_run = false;
int g_port = 0; // the port asked for while g_run
std::atomic<int> g_state{Off};
std::string g_outside;
// Short, so a room that crashed (no close) frees its outside port soon: another room on this network may want it.
constexpr int kLeaseSeconds = 120;
constexpr auto kRenewEvery = std::chrono::seconds(60);
constexpr int kOnlyPermanentLeases = 725; // UPnP OnlyPermanentLeasesSupported: such a router takes lease 0 only

const char* stateName(int s) {
    switch (s) {
    case Open: return "open"; case NoRouter: return "no UPnP router"; case Refused: return "router refused";
    case DoubleNat: return "opened, but the router is behind another NAT";
    case AllTaken: return "every outside port (45000-45007) is held by other rooms"; default: return "?";
    }
}

// Outside ports a room code can hold (RoomCode.kt: 45000 + 0..7).
constexpr int kCodeBase = 45000, kCodePorts = 8;

void work(int port) {
    char portStr[8]; snprintf(portStr, sizeof portStr, "%d", port);
    char extStr[8]; snprintf(extStr, sizeof extStr, "%d", port);
    char lease[12]; snprintf(lease, sizeof lease, "%d", kLeaseSeconds);
    int err = 0;
    auto t0 = std::chrono::steady_clock::now();
    UPNPDev* devs = upnpDiscover(2000, nullptr, nullptr, UPNP_LOCAL_PORT_ANY, 0, 2, &err);
    UPNPUrls urls{}; IGDdatas data{};
    char lan[64] = "", wan[64] = "";
    int igd = devs ? UPNP_GetValidIGD(devs, &urls, &data, lan, sizeof lan, wan, sizeof wan) : UPNP_NO_IGD;
    freeUPNPDevlist(devs);
    bool haveUrls = igd != UPNP_NO_IGD;
    // The outside port [extStr] leads to the room's own port [portStr] on this device. A first add that half
    // worked is undone; a renewal leaves the mapping alone (it runs out by itself if the router is gone).
    auto addBoth = [&](bool renewal = false) -> int {
        int r = UPNP_AddPortMapping(urls.controlURL, data.first.servicetype, extStr, portStr, lan, "Joey64 netplay", "TCP", nullptr, lease);
        if (r == kOnlyPermanentLeases && strcmp(lease, "0") != 0) {
            ULOGI("netplay: UPnP: the router takes only permanent mappings: asking for one (removed when the room closes)");
            snprintf(lease, sizeof lease, "0");
            r = UPNP_AddPortMapping(urls.controlURL, data.first.servicetype, extStr, portStr, lan, "Joey64 netplay", "TCP", nullptr, lease);
        }
        if (r == UPNPCOMMAND_SUCCESS) {
            r = UPNP_AddPortMapping(urls.controlURL, data.first.servicetype, extStr, portStr, lan, "Joey64 netplay", "UDP", nullptr, lease);
            if (r != UPNPCOMMAND_SUCCESS && !renewal) UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, extStr, "TCP", nullptr);
        }
        return r;
    };
    if (igd == UPNP_CONNECTED_IGD || igd == UPNP_PRIVATEIP_IGD) {
        // Another device on this network (another Joey64's room) can hold the outside port already: the router
        // answers 718 (ConflictInMappingEntry). Then the next outside port a room code can hold, the room's own first.
        int r = addBoth();
        for (int i = 0; r == 718 && i < kCodePorts; i++) {
            int ext = kCodeBase + (port - kCodeBase + 1 + i) % kCodePorts;
            if (ext == port) continue;
            ULOGI("netplay: UPnP: outside port %s is taken on the router, trying %d", extStr, ext);
            snprintf(extStr, sizeof extStr, "%d", ext);
            r = addBoth();
        }
        if (r == 718) {
            g_state = AllTaken;
            ULOGI("netplay: UPnP: every outside port a room code can hold (%d-%d) is held by other rooms", kCodeBase, kCodeBase + kCodePorts - 1);
        } else if (r == UPNPCOMMAND_SUCCESS) {
            char ext[64] = "";
            if (UPNP_GetExternalIPAddress(urls.controlURL, data.first.servicetype, ext) != UPNPCOMMAND_SUCCESS || !*ext)
                snprintf(ext, sizeof ext, "%s", wan);
            { std::lock_guard<std::mutex> lk(g_mutex); g_outside = std::string(ext) + ":" + extStr; }
            g_state = igd == UPNP_PRIVATEIP_IGD ? DoubleNat : Open;
        } else {
            g_state = Refused;
            ULOGI("netplay: UPnP: the router refused port %d: %s (%d)", port, strupnperror(r) ? strupnperror(r) : "?", r);
        }
    } else {
        g_state = NoRouter;
    }
    long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    ULOGI("netplay: UPnP: %s (router answer %d, this device %s, outside %s) in %ld ms",
          stateName(g_state), igd, *lan ? lan : "?", g_outside.empty() ? "-" : g_outside.c_str(), ms);

    std::unique_lock<std::mutex> lk(g_mutex);
    int lastRenew = UPNPCOMMAND_SUCCESS;
    while (g_run) {
        if (g_cv.wait_for(lk, kRenewEvery, [] { return !g_run; })) break;
        if ((g_state == Open || g_state == DoubleNat) && strcmp(lease, "0") != 0) {
            lk.unlock();
            int r = addBoth(true);
            // Every minute: logged only when it starts or stops failing.
            if ((r == UPNPCOMMAND_SUCCESS) != (lastRenew == UPNPCOMMAND_SUCCESS))
                ULOGI("netplay: UPnP: renewing outside port %s %s (%d)", extStr, r == UPNPCOMMAND_SUCCESS ? "works again" : "failed", r);
            lastRenew = r;
            lk.lock();
        }
    }
    lk.unlock();
    if (g_state == Open || g_state == DoubleNat) {
        int a = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, extStr, "TCP", nullptr);
        int b = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, extStr, "UDP", nullptr);
        ULOGI("netplay: UPnP: outside port %s closed on the router (%s)", extStr, a == UPNPCOMMAND_SUCCESS && b == UPNPCOMMAND_SUCCESS ? "ok" : "failed");
    }
    if (haveUrls) FreeUPNPUrls(&urls);
}
} // namespace

void open(int port) {
    // Already asked for this port (Play together's room screen opened it before the game's room server starts on the
    // same port): keep that mapping, so the router line and the room code stay as they are.
    { std::lock_guard<std::mutex> lk(g_mutex); if (g_run && g_port == port) return; }
    close();
    { std::lock_guard<std::mutex> lk(g_mutex); g_run = true; g_port = port; g_outside.clear(); }
    g_state = Looking;
    g_thread = std::thread(work, port);
}

void close() {
    { std::lock_guard<std::mutex> lk(g_mutex); g_run = false; }
    g_cv.notify_all();
    if (g_thread.joinable()) g_thread.join();
    g_state = Off;
    std::lock_guard<std::mutex> lk(g_mutex); g_outside.clear();
}

State state() { return (State)g_state.load(); }
std::string outside() { std::lock_guard<std::mutex> lk(g_mutex); return g_outside; }
}
