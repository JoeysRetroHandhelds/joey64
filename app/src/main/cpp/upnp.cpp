// UPnP port mapping for a netplay room: see upnp.h. GPL-3.0-or-later.
// miniupnpc 2.3.3 (BSD-3-Clause, third_party/miniupnpc), as M64Plus AE does for its rooms.
#include "upnp.h"
#include "joeylog.h"

#include "miniupnpc.h"
#include "upnpcommands.h"
#include "upnperrors.h"
#include "natpmp.h"

#include <arpa/inet.h>
#include <sys/select.h>
#include <cstdlib>
#include <cstring>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <functional>
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

// Outside ports a room code can hold (RoomCode.kt: 45000 + 0..7).
constexpr int kCodeBase = 45000, kCodePorts = 8;
bool inCodeRange(int p) { return p >= kCodeBase && p < kCodeBase + kCodePorts; }
// The outside ports to try for the room's own port: its own first, then the others a code can hold.
int codePort(int port, int i) { return i == 0 ? port : kCodeBase + (port - kCodeBase + i) % kCodePorts; }

const char* stateName(int s) {
    switch (s) {
    case Open: return "open"; case NoRouter: return "no UPnP or NAT-PMP router"; case Refused: return "router refused";
    case DoubleNat: return "opened, but the router is behind another NAT";
    case AllTaken: return "every outside port (45000-45007) is held by other rooms"; default: return "?";
    }
}

// NAT-PMP (libnatpmp): one request and its answer, at most `ms` (the library's own retries go on for a minute).
int natpmpAsk(natpmp_t* np, natpmpresp_t* resp, int ms) {
    auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    for (;;) {
        int r = readnatpmpresponseorretry(np, resp);
        if (r != NATPMP_TRYAGAIN) return r;
        auto left = std::chrono::duration_cast<std::chrono::microseconds>(end - std::chrono::steady_clock::now()).count();
        if (left <= 0) return NATPMP_TRYAGAIN;
        timeval tv{}; getnatpmprequesttimeout(np, &tv);
        long long want = (long long)tv.tv_sec * 1000000 + tv.tv_usec;
        if (want > left || want < 0) want = left;
        tv.tv_sec = (time_t)(want / 1000000); tv.tv_usec = (suseconds_t)(want % 1000000);
        fd_set fds; FD_ZERO(&fds); FD_SET(np->s, &fds);
        select(np->s + 1, &fds, nullptr, nullptr, &tv);
    }
}

// Maps (lifetime > 0) the room's `port` to outside port `ext`, or removes it (lifetime 0), for TCP and UDP.
// 0 when the router took both; *got = the outside port the router gave (it may pick another when `ext` is taken).
int natpmpMap(natpmp_t* np, int port, int ext, uint32_t lifetime, int* got = nullptr) {
    int want = ext;
    for (int proto : {NATPMP_PROTOCOL_TCP, NATPMP_PROTOCOL_UDP}) {
        int r = sendnewportmappingrequest(np, proto, (uint16_t)port, (uint16_t)(lifetime ? want : 0), lifetime);
        if (r < 0) return r;
        natpmpresp_t resp{};
        r = natpmpAsk(np, &resp, 2000);
        if (r < 0) return r;
        if (lifetime) {
            int p = resp.pnu.newportmapping.mappedpublicport;
            if (proto == NATPMP_PROTOCOL_TCP) want = p; // UDP asks for the same outside port as TCP got
            else if (p != want) return NATPMP_ERR_UNDEFINEDERROR;
        }
    }
    if (got) *got = want;
    return 0;
}

bool isPrivate(in_addr a) {
    uint32_t h = ntohl(a.s_addr);
    return (h >> 24) == 10 || (h >> 20) == (172u << 4 | 1) || (h >> 16) == (192u << 8 | 168) ||
           (h >> 22) == (100u << 2 | 1) /* 100.64/10, the carrier's NAT */ || h == 0;
}

void work(int port) {
    char portStr[8]; snprintf(portStr, sizeof portStr, "%d", port);
    char extStr[8]; snprintf(extStr, sizeof extStr, "%d", port);
    char lease[12]; snprintf(lease, sizeof lease, "%d", kLeaseSeconds);
    int err = 0;
    auto t0 = std::chrono::steady_clock::now();
    // dev: N64_NETPLAY_UPNP=natpmp tries only NAT-PMP, =upnp only UPnP
    const char* only = getenv("N64_NETPLAY_UPNP");
    const bool skipUpnp = only && !strcmp(only, "natpmp"), skipNatpmp = only && !strcmp(only, "upnp");
    char lan[64] = "", wan[64] = "";

    // NAT-PMP first (2026-11-10): one UDP round trip to the router, so it answers in well under a second (Joey's
    // router: 62 ms, UPnP's discovery and SOAP calls 4.4 s for the same port). The router is the default route's,
    // the app's N64_NETPLAY_GW. A router without NAT-PMP mostly refuses at once (port unreachable); one that keeps
    // quiet costs the 1 s wait before UPnP.
    natpmp_t np{}; bool viaNatpmp = false; np.s = -1;
    int igd = UPNP_NO_IGD;
    {
        const char* gw = skipNatpmp ? nullptr : getenv("N64_NETPLAY_GW");
        in_addr gwa{};
        int r = NATPMP_ERR_CANNOTGETGATEWAY;
        bool answered = false;
        if (gw && inet_pton(AF_INET, gw, &gwa) == 1) r = initnatpmp(&np, 1, gwa.s_addr);
        if (r == 0) {
            natpmpresp_t resp{};
            r = sendpublicaddressrequest(&np);
            if (r >= 0) r = natpmpAsk(&np, &resp, 1000);
            if (r == 0) {
                answered = true;
                char ext[64] = "";
                inet_ntop(AF_INET, &resp.pnu.publicaddress.addr, ext, sizeof ext);
                // The outside port has to be one a room code can hold: the room's own, else the next free one.
                for (int i = 0; i < kCodePorts && !viaNatpmp; i++) {
                    int got = 0;
                    r = natpmpMap(&np, port, codePort(port, i), kLeaseSeconds, &got);
                    if (r != 0) break;
                    if (inCodeRange(got)) {
                        viaNatpmp = true;
                        snprintf(extStr, sizeof extStr, "%d", got);
                    } else {
                        natpmpMap(&np, port, 0, 0); // a port no code can hold: give it back
                        r = NATPMP_ERR_UNDEFINEDERROR;
                    }
                }
                if (viaNatpmp) {
                    { std::lock_guard<std::mutex> lk(g_mutex); g_outside = std::string(ext) + ":" + extStr; }
                    g_state = isPrivate(resp.pnu.publicaddress.addr) ? DoubleNat : Open;
                }
            }
            if (!viaNatpmp) { closenatpmp(&np); np.s = -1; }
        }
        if (!skipNatpmp)
            ULOGI("netplay: NAT-PMP: router %s: %s", gw && *gw ? gw : "not known",
                  viaNatpmp ? "port open" : r == NATPMP_TRYAGAIN ? "no answer" : answered && r == NATPMP_ERR_UNDEFINEDERROR
                  ? "no outside port a room code can hold" : strnatpmperr(r) ? strnatpmperr(r) : "?");
    }

    UPNPUrls urls{}; IGDdatas data{};
    if (!viaNatpmp && !skipUpnp) {
        UPNPDev* devs = upnpDiscover(2000, nullptr, nullptr, UPNP_LOCAL_PORT_ANY, 0, 2, &err);
        igd = devs ? UPNP_GetValidIGD(devs, &urls, &data, lan, sizeof lan, wan, sizeof wan) : UPNP_NO_IGD;
        freeUPNPDevlist(devs);
    }
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
    if (viaNatpmp) {
        // done above
    } else if (igd == UPNP_CONNECTED_IGD || igd == UPNP_PRIVATEIP_IGD) {
        // Another device on this network (another Joey64's room) can hold the outside port already: the router
        // answers 718 (ConflictInMappingEntry). Then the next outside port a room code can hold, the room's own first.
        int r = addBoth();
        for (int i = 1; r == 718 && i < kCodePorts; i++) {
            ULOGI("netplay: UPnP: outside port %s is taken on the router, trying %d", extStr, codePort(port, i));
            snprintf(extStr, sizeof extStr, "%d", codePort(port, i));
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
    ULOGI("netplay: %s: %s (router answer %d, this device %s, outside %s) in %ld ms", viaNatpmp ? "NAT-PMP" : "UPnP",
          stateName(g_state), igd, *lan ? lan : "?", g_outside.empty() ? "-" : g_outside.c_str(), ms);

    std::unique_lock<std::mutex> lk(g_mutex);
    int lastRenew = UPNPCOMMAND_SUCCESS;
    while (g_run) {
        if (g_cv.wait_for(lk, kRenewEvery, [] { return !g_run; })) break;
        if ((g_state == Open || g_state == DoubleNat) && (viaNatpmp || strcmp(lease, "0") != 0)) {
            lk.unlock();
            int r = viaNatpmp ? (natpmpMap(&np, port, atoi(extStr), kLeaseSeconds) == 0 ? UPNPCOMMAND_SUCCESS : -1) : addBoth(true);
            // Every minute: logged only when it starts or stops failing.
            if ((r == UPNPCOMMAND_SUCCESS) != (lastRenew == UPNPCOMMAND_SUCCESS))
                ULOGI("netplay: %s: renewing outside port %s %s (%d)", viaNatpmp ? "NAT-PMP" : "UPnP", extStr,
                      r == UPNPCOMMAND_SUCCESS ? "works again" : "failed", r);
            lastRenew = r;
            lk.lock();
        }
    }
    lk.unlock();
    if (viaNatpmp) {
        int r = natpmpMap(&np, port, 0, 0);
        ULOGI("netplay: NAT-PMP: outside port %s closed on the router (%s)", extStr, r == 0 ? "ok" : "failed");
        closenatpmp(&np);
    } else if (g_state == Open || g_state == DoubleNat) {
        int a = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, extStr, "TCP", nullptr);
        int b = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, extStr, "UDP", nullptr);
        ULOGI("netplay: UPnP: outside port %s closed on the router (%s)", extStr, a == UPNPCOMMAND_SUCCESS && b == UPNPCOMMAND_SUCCESS ? "ok" : "failed");
    }
    if (haveUrls) FreeUPNPUrls(&urls);
}
} // namespace

namespace {
// Opens and closes run on one thread of their own, one at a time and in the order asked (code audit 3, M13: a close
// joined the worker on the caller's thread, the main thread when a room's game quit, for as long as a slow router took;
// and two closes at once from two threads could join the same thread, which ends the app). Started at the first ask.
std::mutex g_qMutex;
std::condition_variable g_qCv;
std::deque<std::function<void()>> g_q;
bool g_qStarted = false;

void post(std::function<void()> f) {
    std::lock_guard<std::mutex> lk(g_qMutex);
    g_q.push_back(std::move(f));
    if (!g_qStarted) {
        g_qStarted = true;
        std::thread([] {
            for (;;) {
                std::function<void()> job;
                {
                    std::unique_lock<std::mutex> lk(g_qMutex);
                    g_qCv.wait(lk, [] { return !g_q.empty(); });
                    job = std::move(g_q.front()); g_q.pop_front();
                }
                job();
            }
        }).detach();
    }
    g_qCv.notify_one();
}

void closeNow() {
    { std::lock_guard<std::mutex> lk(g_mutex); g_run = false; }
    g_cv.notify_all();
    if (g_thread.joinable()) g_thread.join();
    g_state = Off;
    std::lock_guard<std::mutex> lk(g_mutex); g_outside.clear();
}

void openNow(int port) {
    // Already asked for this port (the room screen opened it before the game's room server starts on the same
    // port): keep that mapping, so the router line and the room code stay as they are.
    { std::lock_guard<std::mutex> lk(g_mutex); if (g_run && g_port == port) return; }
    closeNow();
    { std::lock_guard<std::mutex> lk(g_mutex); g_run = true; g_port = port; g_outside.clear(); }
    g_state = Looking;
    g_thread = std::thread(work, port);
}
} // namespace

void open(int port) { post([port] { openNow(port); }); }

// Off at once, so a new room's router line doesn't read the old room's mapping while this one is still being removed.
void close() {
    g_state = Off;
    { std::lock_guard<std::mutex> lk(g_mutex); g_outside.clear(); }
    post([] { closeNow(); });
}

State state() { return (State)g_state.load(); }
std::string outside() { std::lock_guard<std::mutex> lk(g_mutex); return g_outside; }
}
