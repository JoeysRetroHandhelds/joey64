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
#include <mutex>
#include <thread>

#define ULOGI(...) joeyLog(ANDROID_LOG_INFO, "netplay", __VA_ARGS__)

namespace Upnp {
namespace {
std::thread g_thread;
std::mutex g_mutex;
std::condition_variable g_cv;
bool g_run = false;
std::atomic<int> g_state{Off};
std::string g_outside;
constexpr int kLeaseSeconds = 3600; // the router drops it by itself an hour after a crash
constexpr auto kRenewEvery = std::chrono::minutes(30);

const char* stateName(int s) {
    switch (s) {
    case Open: return "open"; case NoRouter: return "no UPnP or NAT-PMP router"; case Refused: return "router refused";
    case DoubleNat: return "opened, but the router is behind another NAT"; default: return "?";
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

// Maps (lifetime > 0) or removes (0) the port for TCP and UDP; 0 when the router took both.
int natpmpMap(natpmp_t* np, int port, uint32_t lifetime) {
    for (int proto : {NATPMP_PROTOCOL_TCP, NATPMP_PROTOCOL_UDP}) {
        int r = sendnewportmappingrequest(np, proto, (uint16_t)port, (uint16_t)port, lifetime);
        if (r < 0) return r;
        natpmpresp_t resp{};
        r = natpmpAsk(np, &resp, 2000);
        if (r < 0) return r;
        if (lifetime && resp.pnu.newportmapping.mappedpublicport != port) return NATPMP_ERR_UNDEFINEDERROR; // another port: joiners use ours
    }
    return 0;
}

bool isPrivate(in_addr a) {
    uint32_t h = ntohl(a.s_addr);
    return (h >> 24) == 10 || (h >> 20) == (172u << 4 | 1) || (h >> 16) == (192u << 8 | 168) ||
           (h >> 22) == (100u << 2 | 1) /* 100.64/10, the carrier's NAT */ || h == 0;
}

void work(int port) {
    char portStr[8]; snprintf(portStr, sizeof portStr, "%d", port);
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
        if (gw && inet_pton(AF_INET, gw, &gwa) == 1) r = initnatpmp(&np, 1, gwa.s_addr);
        if (r == 0) {
            natpmpresp_t resp{};
            r = sendpublicaddressrequest(&np);
            if (r >= 0) r = natpmpAsk(&np, &resp, 1000);
            if (r == 0) {
                char ext[64] = "";
                inet_ntop(AF_INET, &resp.pnu.publicaddress.addr, ext, sizeof ext);
                r = natpmpMap(&np, port, kLeaseSeconds);
                if (r == 0) {
                    viaNatpmp = true;
                    { std::lock_guard<std::mutex> lk(g_mutex); g_outside = std::string(ext) + ":" + portStr; }
                    g_state = isPrivate(resp.pnu.publicaddress.addr) ? DoubleNat : Open;
                }
            }
            if (!viaNatpmp) { closenatpmp(&np); np.s = -1; }
        }
        if (!skipNatpmp)
            ULOGI("netplay: NAT-PMP: router %s: %s", gw && *gw ? gw : "not known",
                  viaNatpmp ? "port open" : r == NATPMP_TRYAGAIN ? "no answer" : strnatpmperr(r) ? strnatpmperr(r) : "?");
    }

    UPNPUrls urls{}; IGDdatas data{};
    if (!viaNatpmp && !skipUpnp) {
        UPNPDev* devs = upnpDiscover(2000, nullptr, nullptr, UPNP_LOCAL_PORT_ANY, 0, 2, &err);
        igd = devs ? UPNP_GetValidIGD(devs, &urls, &data, lan, sizeof lan, wan, sizeof wan) : UPNP_NO_IGD;
        freeUPNPDevlist(devs);
    }
    bool haveUrls = igd != UPNP_NO_IGD;
    auto addBoth = [&]() -> int {
        int r = UPNP_AddPortMapping(urls.controlURL, data.first.servicetype, portStr, portStr, lan, "Joey64 netplay", "TCP", nullptr, lease);
        if (r == UPNPCOMMAND_SUCCESS)
            r = UPNP_AddPortMapping(urls.controlURL, data.first.servicetype, portStr, portStr, lan, "Joey64 netplay", "UDP", nullptr, lease);
        return r;
    };
    if (viaNatpmp) {
        // done above
    } else if (igd == UPNP_CONNECTED_IGD || igd == UPNP_PRIVATEIP_IGD) {
        int r = addBoth();
        if (r == UPNPCOMMAND_SUCCESS) {
            char ext[64] = "";
            if (UPNP_GetExternalIPAddress(urls.controlURL, data.first.servicetype, ext) != UPNPCOMMAND_SUCCESS || !*ext)
                snprintf(ext, sizeof ext, "%s", wan);
            { std::lock_guard<std::mutex> lk(g_mutex); g_outside = std::string(ext) + ":" + portStr; }
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
    while (g_run) {
        if (g_cv.wait_for(lk, kRenewEvery, [] { return !g_run; })) break;
        if (g_state == Open || g_state == DoubleNat) {
            lk.unlock();
            int r = viaNatpmp ? (natpmpMap(&np, port, kLeaseSeconds) == 0 ? UPNPCOMMAND_SUCCESS : -1) : addBoth();
            ULOGI("netplay: %s: renewed port %d: %s", viaNatpmp ? "NAT-PMP" : "UPnP", port, r == UPNPCOMMAND_SUCCESS ? "ok" : "failed");
            lk.lock();
        }
    }
    lk.unlock();
    if (viaNatpmp) {
        int r = natpmpMap(&np, port, 0);
        ULOGI("netplay: NAT-PMP: port %d closed on the router (%s)", port, r == 0 ? "ok" : "failed");
        closenatpmp(&np);
    } else if (g_state == Open || g_state == DoubleNat) {
        int a = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, portStr, "TCP", nullptr);
        int b = UPNP_DeletePortMapping(urls.controlURL, data.first.servicetype, portStr, "UDP", nullptr);
        ULOGI("netplay: UPnP: port %d closed on the router (%s)", port, a == UPNPCOMMAND_SUCCESS && b == UPNPCOMMAND_SUCCESS ? "ok" : "failed");
    }
    if (haveUrls) FreeUPNPUrls(&urls);
}
} // namespace

void open(int port) {
    close();
    { std::lock_guard<std::mutex> lk(g_mutex); g_run = true; g_outside.clear(); }
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
