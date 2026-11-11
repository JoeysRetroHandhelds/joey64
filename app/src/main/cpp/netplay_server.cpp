// Netplay room server: see netplay_server.h. GPL-3.0-or-later.
#include "netplay_server.h"
#include "joeylog.h"
#include "upnp.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "netplay"
#define NLOGI(...) joeyLog(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define NLOGE(...) joeyLog(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace NetplayServer {
namespace {

// The core's packet ids (src/main/netplay.c)
enum : uint8_t { UDP_SEND_KEY_INFO = 0, UDP_RECEIVE_KEY_INFO = 1, UDP_REQUEST_KEY_INFO = 2,
                 UDP_RECEIVE_KEY_INFO_GRATUITOUS = 3, UDP_SYNC_DATA = 4,
                 // Joey64's own (2026-11-10): [5, player 0-3, paused 0/1], sent by the app (not the core) every
                 // 0.5 s while in a room, so a lost one is made good
                 UDP_PAUSE_NOTICE = 5,
                 // [8, player, n, n x (count32, keys32, plugin)]: a player's last inputs in one packet, which the
                 // core sends while it waits (2026-11-10, NP_RESEND 20: more than the largest adaptive delay)
                 UDP_JOEY_KEY_BATCH = 8 };
enum : uint8_t { TCP_SEND_SAVE = 1, TCP_RECEIVE_SAVE = 2, TCP_SEND_SETTINGS = 3, TCP_RECEIVE_SETTINGS = 4,
                 TCP_REGISTER_PLAYER = 5, TCP_GET_REGISTRATION = 6, TCP_DISCONNECT_NOTICE = 7,
                 // Joey64's own (2026-11-10): [0x40, ROM MD5 as 32 hex characters], answered 2 = the host's ROM,
                 // 1 = another one, 0 = no host ROM within 30 s. The host's is the one from this device (127.0.0.1).
                 TCP_JOEY_ROM_CHECK = 0x40 };
constexpr int kSettingsSize = 24;     // SETTINGS_SIZE: six 32-bit settings
constexpr int kMaxPerPacket = 56;     // (512 - 5) / 9 input events in one reply
constexpr uint8_t kBufferTarget = 2;  // the client's local buffer above which it may catch up (fast-forward)
// What the network can make the room hold (code audit 3, H8: a client could grow its buffer, the saves, the
// connections and the UDP maps without end, until the low-memory killer ended the host's game)
constexpr size_t kMaxIn = 4096;          // a connection's bytes not handled yet; player 1's game may send a save on top
constexpr uint32_t kMaxSave = 1u << 20;  // one save file (the core's largest, FlashRAM or the Controller Paks, is 128 KB)
constexpr size_t kMaxSaves = 8;          // save files kept (the core sends eep, sra, fla and mpk)
constexpr size_t kMaxExt = 32;           // a save file's extension
constexpr size_t kMaxWaitingSaves = 8;   // saves one connection waits for
constexpr size_t kMaxOut = 4u << 20;     // bytes waiting for a connection that stopped reading: it's dropped (H7)
constexpr size_t kMaxClients = 8;        // connections: four players' games, with room to spare
constexpr int kRegisterSecs = 60;        // a connection that hasn't registered by then is closed (the core does within ~30 s)
constexpr size_t kMaxPeers = 8;          // addresses inputs are sent to
constexpr size_t kMaxMapped = 8192;      // the adaptive delay's slot maps

uint32_t r32(const uint8_t* p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
void w32(uint8_t* p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = (uint8_t)v; }

struct Input { uint32_t keys; uint8_t plugin; };
struct Reg { uint32_t id = 0; uint8_t plugin = 0, raw = 0; };
struct Client {
    int fd = -1;
    std::vector<uint8_t> in;          // bytes not handled yet
    uint32_t regId = 0;               // from its TCP_REGISTER_PLAYER
    bool waitingRegistration = false; // a TCP_GET_REGISTRATION not answered yet
    std::chrono::steady_clock::time_point regAskedAt;
    bool waitingSettings = false;
    std::vector<std::string> waitingSaves; // TCP_RECEIVE_SAVE extensions not answered yet
    bool local = false;               // connected from this device: the host's own game
    std::string romAsked;             // a TCP_JOEY_ROM_CHECK waiting for the host's ROM
    std::chrono::steady_clock::time_point romAskedAt;
    // Sent from the room thread without ever waiting (code audit 3, H7): what didn't go yet waits here for POLLOUT
    std::vector<uint8_t> out;
    size_t outAt = 0;
    std::chrono::steady_clock::time_point at; // when it connected
    sockaddr_storage addr{};          // where from: its game's UDP must come from the same address (M21)
    bool drop = false;                // closed at the end of this pass
};
struct Peer { sockaddr_storage addr; socklen_t len; };

std::atomic<bool> g_running{false};
std::mutex g_life; // start() and stop(), from the main thread and the emulation thread (code audit 3, p5 L3)
std::atomic<uint8_t> g_regMask{0}, g_status{0};
std::atomic<int> g_delay{0};
std::thread g_thread;
int g_wake[2] = {-1, -1};

struct Room {
    int players = 2, delay = 2;
    int tcp = -1, udp = -1;
    std::vector<Client> clients;
    Reg reg[4];
    bool disconnected[4] = {};
    std::map<uint32_t, Input> inputs[4];
    std::map<uint32_t, uint32_t> lastAsked[4]; // reg id -> last event count it asked for, per player
    std::vector<Peer> peers;
    std::vector<uint8_t> settings; bool haveSettings = false;
    std::map<std::string, std::vector<uint8_t>> saves;
    std::map<uint32_t, std::vector<uint8_t>> sync; // VI count -> the first CP0 report
    std::map<uint32_t, int> syncReports;           // VI count -> reports that matched the first
    int syncMatched = 0;                           // VI counts where every player's report matched
    uint8_t status = 0;
    uint8_t pausedMask = 0; // players whose app is paused (pause menu, in the background)
    // Adaptive input delay (Joey64 2026-11-10; start() with delay -1). For each input and each player's game, the
    // room works out how late it came: when the room got it, plus that player's one-way time, against when that
    // game reached the frame (its first request for it, less its one-way time; one-way = half its own ping).
    // Every 4 s: if 10% or more of them came late, a frame of delay is added; if under 3% did and 95% came at
    // least 20 ms early, for 15 s (twice as long each time a frame taken away had to come back within a minute,
    // up to 2 min), one is taken away.
    // A dropout makes one input very late for each game (the games stop at it), so it barely counts: more delay
    // would not have helped. A player's input sent at its event count c goes to slot c + its delay; sched keeps from which count on each delay holds, so resends land where the
    // first copy did. Adding a frame fills the next slot with a copy of the last input; taking one away drops one
    // input (one that repeats the one before when there is one within 60 frames, so no short press is lost).
    bool adaptive = false;
    static constexpr int kMinDelay = 2, kMaxDelay = 12;
    std::vector<std::pair<uint32_t, int>> sched[4]; // from client event count -> slot offset
    uint32_t hi[4] = {}; bool started[4] = {};       // newest client event count heard per player
    int pendingLower[4] = {};                        // inputs left before a frame is dropped anyway, 0 = none
    using tp = std::chrono::steady_clock::time_point;
    std::map<uint64_t, tp> missing; // player << 32 | slot -> first asked before any input was there (holes, below)
    struct SlotTimes { tp arr, req[4]; bool hasArr = false; uint8_t reqMask = 0; };
    std::map<uint64_t, SlotTimes> times; // player << 32 | slot -> when the room got it, when each game first asked
    std::vector<float> lateMs;            // this window's lateness samples in ms (> 0: that game waited for it)
    void sample(int r, tp arr, tp req) {
        lateMs.push_back((float)(std::chrono::duration<double, std::milli>(arr - req).count() + rosterPing[r]));
    }
    void arrived(int p, uint32_t slot) {
        if (!adaptive) return;
        if (times.size() >= kMaxMapped) times.erase(times.begin());
        auto& s = times[(uint64_t)p << 32 | slot];
        if (s.hasArr) return;
        s.arr = std::chrono::steady_clock::now(); s.hasArr = true;
        for (int r = 0; r < 4; r++) if (s.reqMask & (1 << r)) sample(r, s.arr, s.req[r]);
    }
    void asked(int p, uint32_t slot, int r) {
        if (!adaptive || r < 0 || !active(p)) return;
        if (times.size() >= kMaxMapped) times.erase(times.begin());
        auto& s = times[(uint64_t)p << 32 | slot];
        if (s.reqMask & (1 << r)) return;
        s.reqMask |= (uint8_t)(1 << r); s.req[r] = std::chrono::steady_clock::now();
        if (s.hasArr) sample(r, s.arr, s.req[r]);
    }
    std::chrono::steady_clock::time_point windowAt = std::chrono::steady_clock::now(), calmSince = windowAt,
        holdUntil = windowAt, lastLowerAt = windowAt - std::chrono::minutes(5);
    int calmNeed = 15;
    int offsetOf(int p, uint32_t c) const {
        for (auto it = sched[p].rbegin(); it != sched[p].rend(); ++it)
            if (c - it->first < 0x80000000u) return it->second;
        return sched[p].empty() ? delay : sched[p].front().second;
    }
    // A player's input into a slot: everyone gets it at once (the "gratuitous" reply), so nobody waits for a request round trip
    void put(int p, uint32_t slot, Input in) {
        inputs[p][slot] = in;
        missing.erase((uint64_t)p << 32 | slot);
        arrived(p, slot);
        for (auto& peer : peers) sendInputs(peer.addr, peer.len, UDP_RECEIVE_KEY_INFO_GRATUITOUS, p, slot, 0);
    }
    bool active(int i) const { return reg[i].id && !disconnected[i]; }
    void changeDelay(int to, const char* why) {
        NLOGI("netplay server: input delay %d -> %d (%s)", delay, to, why);
        for (int i = 0; i < 4; i++) {
            if (!active(i) || !started[i]) continue;
            if (to > delay) {
                int off = offsetOf(i, hi[i]); uint32_t slot = hi[i] + (uint32_t)off;
                auto it = inputs[i].find(slot);
                Input copy = it != inputs[i].end() ? it->second : Input{0, reg[i].plugin};
                if (!inputs[i].count(slot + 1)) put(i, slot + 1, copy);
                sched[i].push_back({hi[i] + 1, off + 1});
            } else pendingLower[i] = 60;
            while (sched[i].size() > 8) sched[i].erase(sched[i].begin());
        }
        delay = to;
    }
    void adapt() {
        if (!adaptive) return;
        using namespace std::chrono;
        auto now = steady_clock::now();
        bool quiet = pausedMask != 0;
        for (int i = 0; i < 4; i++) if (active(i) && !started[i]) quiet = true;
        if (quiet || now < holdUntil) {
            lateMs.clear(); windowAt = now;
            if (quiet) { calmSince = now; holdUntil = now + seconds(3); }
            return;
        }
        for (int i = 0; i < 4; i++) if (pendingLower[i] && active(i)) return; // a frame is still being taken away
        if (now - windowAt < seconds(4)) return;
        windowAt = now;
        // forget slot times older than 3 s
        for (auto it = times.begin(); it != times.end();) {
            tp t = it->second.hasArr ? it->second.arr : it->second.req[__builtin_ctz(it->second.reqMask | 0x10) & 3];
            if (now - t > seconds(3)) it = times.erase(it); else ++it;
        }
        size_t n = lateMs.size();
        if (n < 40) { lateMs.clear(); calmSince = now; return; } // too few to say
        size_t waited = 0; for (float v : lateMs) waited += v > 0;
        std::nth_element(lateMs.begin(), lateMs.begin() + n * 95 / 100, lateMs.end());
        float p95 = lateMs[n * 95 / 100];
        lateMs.clear();
        double frac = (double)waited / n;
        NLOGI("netplay server: delay %d: %.0f%% of %zu inputs late, 95%% by %.0f ms or less", delay, frac * 100, n, p95);
        char why[64];
        if (frac >= 0.10 && delay < kMaxDelay) {
            snprintf(why, sizeof why, "%.0f%% of inputs late", frac * 100);
            if (now - lastLowerAt < seconds(60)) calmNeed = std::min(calmNeed * 2, 120);
            changeDelay(delay + 1, why);
            holdUntil = now + seconds(2); calmSince = now;
        } else if (frac >= 0.03 || p95 > -20) calmSince = now;
        else if (now - calmSince >= seconds(calmNeed) && delay > kMinDelay) {
            snprintf(why, sizeof why, "calm for %d s", calmNeed);
            changeDelay(delay - 1, why);
            calmSince = lastLowerAt = now;
        }
    }
    // Each player's name and own ping, from its app's pause notices; sent to every app once a second as
    // [7, count, then per player: player 0-3, ping ms u16, name length, name] (Joey64 2026-11-10, netplayPlayers)
    std::string rosterName[4];
    uint16_t rosterPing[4] = {};
    sockaddr_storage rosterAddr[4];
    socklen_t rosterAddrLen[4] = {};
    std::chrono::steady_clock::time_point rosterAt = std::chrono::steady_clock::now();
    void sendRoster() {
        auto now = std::chrono::steady_clock::now();
        if (now - rosterAt < std::chrono::seconds(1)) return;
        rosterAt = now;
        uint8_t out[512]; size_t k = 2; uint8_t cnt = 0;
        for (int i = 0; i < 4; i++) {
            if (!reg[i].id || disconnected[i]) continue;
            size_t l = std::min<size_t>(rosterName[i].size(), 40);
            out[k++] = (uint8_t)i; out[k++] = rosterPing[i] >> 8; out[k++] = (uint8_t)rosterPing[i];
            out[k++] = (uint8_t)l; memcpy(out + k, rosterName[i].data(), l); k += l; cnt++;
        }
        out[0] = 7; out[1] = cnt;
        out[k++] = (uint8_t)delay; // after the players: the room's input delay now (Joey64 2026-11-10)
        for (int i = 0; i < 4; i++) if (rosterAddrLen[i] && !disconnected[i]) udpSend(out, k, rosterAddr[i], rosterAddrLen[i]);
    }
    // Dev (2026-11-10): N64_NETPLAY_SIM=<delay ms>:<jitter ms>:<loss %> makes every UDP packet the room sends late by
    // delay plus up to jitter, and drops loss % of them, so an internet connection can be tried on one Wi-Fi.
    // Both ways are covered for a joiner, as its inputs come back to it through the room too.
    int simDelay = 0, simJitter = 0, simLoss = 0;
    std::mt19937 simRng{12345};
    struct Late { std::chrono::steady_clock::time_point at; sockaddr_storage to; socklen_t len; std::vector<uint8_t> d; };
    std::deque<Late> late;
    uint32_t simDropped = 0, simSent = 0;
    void udpSend(const uint8_t* d, size_t n, const sockaddr_storage& to, socklen_t len) {
        if (!simDelay && !simJitter && !simLoss) { sendto(udp, d, n, 0, (const sockaddr*)&to, len); return; }
        ++simSent;
        if (simLoss && (int)(simRng() % 100) < simLoss) { ++simDropped; return; }
        int ms = simDelay + (simJitter ? (int)(simRng() % (uint32_t)(simJitter + 1)) : 0);
        Late l{std::chrono::steady_clock::now() + std::chrono::milliseconds(ms), to, len, std::vector<uint8_t>(d, d + n)};
        // kept in send-time order; jitter may reorder packets, as the internet can
        auto it = late.end();
        while (it != late.begin() && (it - 1)->at > l.at) --it;
        late.insert(it, std::move(l));
    }
    void sendLate() {
        auto now = std::chrono::steady_clock::now();
        while (!late.empty() && late.front().at <= now) {
            auto& l = late.front();
            sendto(udp, l.d.data(), l.d.size(), 0, (const sockaddr*)&l.to, l.len);
            late.pop_front();
        }
    }
    // Connection numbers per player, logged every 10 s: new inputs, copies (resends), requests, last packet heard.
    struct NetStats { uint32_t inputs = 0, copies = 0, requests = 0; std::chrono::steady_clock::time_point heard; bool any = false; };
    NetStats ns[4];
    std::chrono::steady_clock::time_point nsSince = std::chrono::steady_clock::now();
    void heard(int p) { ns[p].heard = std::chrono::steady_clock::now(); ns[p].any = true; }
    void logNet() {
        auto now = std::chrono::steady_clock::now();
        double secs = std::chrono::duration<double>(now - nsSince).count();
        if (secs < 10) return;
        std::string line;
        char b[160];
        for (int i = 0; i < 4; i++) {
            if (!reg[i].id) continue;
            long ago = ns[i].any ? (long)std::chrono::duration_cast<std::chrono::milliseconds>(now - ns[i].heard).count() : -1;
            snprintf(b, sizeof b, "%splayer %d: %u inputs, %u copies, %u requests, heard %ld ms ago%s", line.empty() ? "" : "; ",
                     i + 1, ns[i].inputs, ns[i].copies, ns[i].requests, ago, disconnected[i] ? " (left)" : (pausedMask & (1 << i)) ? " (paused)" : "");
            line += b;
            ns[i].inputs = ns[i].copies = ns[i].requests = 0;
        }
        if (!line.empty()) NLOGI("netplay server: net in %.1f s: %s", secs, line.c_str());
        if (simSent) { NLOGI("netplay server: simulated internet: %u of %u packets dropped", simDropped, simSent); simSent = simDropped = 0; }
        nsSince = now;
    }
    // The status byte sent: bit 0 de-synced, bits 1-4 player 1-4 left, bits 5-7 the first paused player (1-4), 0 = none.
    uint8_t statusByte() const {
        uint8_t p = 0;
        for (int i = 0; i < 4; i++) if (pausedMask & (1 << i)) { p = (uint8_t)(i + 1); break; }
        return (uint8_t)((status & 0x1F) | (p << 5));
    }

    int registered() const { int n = 0; for (auto& r : reg) n += r.id != 0; return n; }
    int playerOf(uint32_t id) const { for (int i = 0; i < 4; i++) if (id && reg[i].id == id) return i; return -1; }

    // A message to a connection: sent as far as its socket takes now, the rest when poll says it can take more.
    // One that has more than kMaxOut waiting stopped reading: it's dropped rather than holding every player.
    void queue(Client& c, const uint8_t* d, size_t n) {
        if (c.drop) return;
        if (c.out.size() - c.outAt + n > kMaxOut) {
            NLOGE("netplay server: a connection stopped reading (%zu bytes waiting), closed", c.out.size() - c.outAt);
            c.drop = true; return;
        }
        c.out.insert(c.out.end(), d, d + n);
        flush(c);
    }
    void flush(Client& c) {
        while (c.outAt < c.out.size()) {
            ssize_t k = send(c.fd, c.out.data() + c.outAt, c.out.size() - c.outAt, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (k < 0 && errno == EINTR) continue;
            if (k < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
            if (k <= 0) { c.drop = true; return; }
            c.outAt += (size_t)k;
        }
        if (c.outAt == c.out.size()) { c.out.clear(); c.outAt = 0; }
        else if (c.outAt >= (1u << 16)) { c.out.erase(c.out.begin(), c.out.begin() + (long)c.outAt); c.outAt = 0; }
    }
    // The address without its port, IPv4 as IPv4-mapped IPv6, so a game's TCP and UDP compare alike.
    static bool sameHost(const sockaddr_storage& a, const sockaddr_storage& b) {
        auto v6 = [](const sockaddr_storage& s, uint8_t out[16]) {
            memset(out, 0, 16);
            if (s.ss_family == AF_INET6) { memcpy(out, &((const sockaddr_in6&)s).sin6_addr, 16); return true; }
            if (s.ss_family == AF_INET) { out[10] = out[11] = 0xFF; memcpy(out + 12, &((const sockaddr_in&)s).sin_addr, 4); return true; }
            return false;
        };
        uint8_t x[16], y[16];
        return v6(a, x) && v6(b, y) && memcmp(x, y, 16) == 0;
    }
    // UDP is taken only from where a registered player's game connected (code audit 3, M21: any sender could post any
    // player's inputs, pause them, flag a de-sync, and use the room to reflect traffic at a forged address).
    const Client* clientOf(int player) const {
        if (player < 0 || player > 3 || !reg[player].id) return nullptr;
        for (auto& c : clients) if (c.regId == reg[player].id && !c.drop) return &c;
        return nullptr;
    }
    bool fromPlayer(const sockaddr_storage& from, int player) const {
        const Client* c = clientOf(player);
        return c && sameHost(c->addr, from);
    }
    bool fromAnyPlayer(const sockaddr_storage& from) const {
        for (int i = 0; i < 4; i++) if (fromPlayer(from, i)) return true;
        return false;
    }
    // The game that feeds saves and settings: player 1's (the host's own, or a joiner's when the host moved itself)
    bool feeds(const Client& c) const { return c.local || (c.regId && c.regId == reg[0].id); }
    void addPeer(const sockaddr_storage& a, socklen_t len) {
        for (auto& p : peers) if (p.len == len && memcmp(&p.addr, &a, len) == 0) return;
        if (peers.size() >= kMaxPeers) return;
        peers.push_back({a, len});
    }
    void markLeft(int player) {
        if (player < 0 || disconnected[player]) return;
        disconnected[player] = true;
        status |= (uint8_t)(1 << (player + 1));
        pausedMask &= (uint8_t)~(1 << player);
        NLOGI("netplay server: player %d left", player + 1);
    }
    // A player who left gets blank input on demand, so the others can go on.
    void fillLeft(int p, uint32_t count) {
        if (!disconnected[p]) return;
        for (uint32_t c = count; c < count + (uint32_t)delay + 1; c++)
            if (!inputs[p].count(c)) inputs[p][c] = {0, reg[p].plugin};
        // the others keep asking for the left player's inputs: keep the last ~10 s, as gotInput does (code audit 3, H8:
        // they piled up for the rest of the session)
        while (!inputs[p].empty() && inputs[p].begin()->first + 600 < count) inputs[p].erase(inputs[p].begin());
    }

    // --- TCP ---
    std::string hostRom;
    void answerRom(Client& c, uint8_t a) {
        queue(c, &a, 1);
        NLOGI("netplay server: ROM check %s from %08x: %s", c.romAsked.c_str(), c.regId,
              a == 2 ? "the host's" : a == 1 ? "NOT the host's" : "no host ROM yet");
        c.romAsked.clear();
    }
    void answerPending(Client& c) {
        if (!c.romAsked.empty()) {
            if (!hostRom.empty()) answerRom(c, c.romAsked == hostRom ? 2 : 1);
            else if (std::chrono::steady_clock::now() - c.romAskedAt > std::chrono::seconds(30)) answerRom(c, 0);
        }
        if (c.waitingRegistration && (registered() >= players ||
                std::chrono::steady_clock::now() - c.regAskedAt > std::chrono::seconds(30))) {
            uint8_t out[24]; size_t k = 0;
            for (int i = 0; i < 4; i++) { w32(out + k, reg[i].id); k += 4; out[k++] = reg[i].plugin; out[k++] = reg[i].raw; }
            queue(c, out, sizeof out);
            c.waitingRegistration = false;
            NLOGI("netplay server: registration sent to %08x (%d of %d players)", c.regId, registered(), players);
        }
        if (c.waitingSettings && haveSettings) {
            queue(c, settings.data(), settings.size());
            c.waitingSettings = false;
        }
        for (size_t i = 0; i < c.waitingSaves.size();) {
            auto it = saves.find(c.waitingSaves[i]);
            if (it == saves.end()) { i++; continue; }
            queue(c, it->second.data(), it->second.size());
            c.waitingSaves.erase(c.waitingSaves.begin() + (long)i);
        }
    }
    // One whole message from the start of c.in, or 0 if more bytes are needed; (size_t)-1 closes the connection.
    size_t handleTcp(Client& c) {
        const auto& b = c.in;
        if (b.empty()) return 0;
        // A save's extension, NUL-ended right after the id: its end, 0 while more bytes are needed, -1 when too long
        auto extEnd = [&]() -> long {
            for (size_t i = 1; i < b.size() && i <= kMaxExt + 1; i++) if (!b[i]) return (long)i;
            return b.size() > kMaxExt + 1 ? -1 : 0;
        };
        switch (b[0]) {
        case TCP_REGISTER_PLAYER: {
            if (b.size() < 8) return 0;
            int p = b[1]; uint32_t id = r32(&b[4]);
            uint8_t ok = 0;
            // one place a connection (M21: one connection could take every free place)
            if (p >= 0 && p < 4 && id && (c.regId == 0 || c.regId == id) && (reg[p].id == 0 || reg[p].id == id)) {
                reg[p] = {id, b[2], b[3]}; ok = 1; c.regId = id;
                uint8_t m = 0; for (int i = 0; i < 4; i++) if (reg[i].id) m |= 1 << i;
                g_regMask = m;
                // the first `delay` events of each player are blank, which is what gives the input delay
                for (uint32_t k = 0; k < (uint32_t)delay; k++) inputs[p][k] = {0, b[2]};
                sched[p] = {{0u, delay}}; started[p] = false; pendingLower[p] = 0;
            }
            NLOGI("netplay server: player %d register %08x: %s", p + 1, id, ok ? "ok" : "taken");
            uint8_t out[2] = {ok, kBufferTarget};
            queue(c, out, 2);
            return 8;
        }
        case TCP_GET_REGISTRATION:
            c.waitingRegistration = true; c.regAskedAt = std::chrono::steady_clock::now();
            return 1;
        case TCP_SEND_SETTINGS:
            if (b.size() < 1 + kSettingsSize) return 0;
            // only player 1's game sets them for everyone (M21)
            if (!feeds(c)) { NLOGE("netplay server: settings from a game that isn't player 1's, closing that connection"); return (size_t)-1; }
            settings.assign(b.begin() + 1, b.begin() + 1 + kSettingsSize); haveSettings = true;
            return 1 + kSettingsSize;
        case TCP_RECEIVE_SETTINGS:
            c.waitingSettings = true;
            return 1;
        case TCP_SEND_SAVE: {
            long e = extEnd();
            if (e < 0) { NLOGE("netplay server: a save's name too long, closing that connection"); return (size_t)-1; }
            if (e == 0 || b.size() < (size_t)e + 5) return 0;
            // only player 1's game gives everyone its saves (M21: any connection could replace them)
            if (!feeds(c)) { NLOGE("netplay server: a save from a game that isn't player 1's, closing that connection"); return (size_t)-1; }
            uint32_t size = r32(&b[e + 1]);
            if (size > kMaxSave) { NLOGE("netplay server: a %u-byte save, more than any game has, closing that connection", size); return (size_t)-1; }
            if (b.size() < (size_t)e + 5 + size) return 0;
            std::string ext((const char*)&b[1]);
            if (saves.size() < kMaxSaves || saves.count(ext)) {
                saves[ext].assign(b.begin() + e + 5, b.begin() + e + 5 + size);
                NLOGI("netplay server: save .%s from player 1 (%u bytes)", ext.c_str(), size);
            } else NLOGE("netplay server: save .%s not kept: %zu saves already", ext.c_str(), saves.size());
            return (size_t)e + 5 + size;
        }
        case TCP_RECEIVE_SAVE: {
            long e = extEnd();
            if (e < 0) { NLOGE("netplay server: a save's name too long, closing that connection"); return (size_t)-1; }
            if (e == 0) return 0;
            if (c.waitingSaves.size() >= kMaxWaitingSaves) { NLOGE("netplay server: too many saves asked for, closing that connection"); return (size_t)-1; }
            c.waitingSaves.emplace_back((const char*)&b[1]);
            return (size_t)e + 1;
        }
        case TCP_JOEY_ROM_CHECK: {
            if (b.size() < 33) return 0;
            std::string md5((const char*)&b[1], 32);
            if (c.local && hostRom.empty()) { hostRom = md5; NLOGI("netplay server: host ROM %s", md5.c_str()); }
            c.romAsked = md5; c.romAskedAt = std::chrono::steady_clock::now();
            return 33;
        }
        case TCP_DISCONNECT_NOTICE: {
            if (b.size() < 5) return 0;
            // a game says it leaves itself, no other (M21: the ids TCP_GET_REGISTRATION hands out let anyone kick a player)
            uint32_t id = r32(&b[1]);
            if (id && id == c.regId) markLeft(playerOf(id));
            else NLOGE("netplay server: a leave notice for another player's id, ignored");
            return 5;
        }
        default:
            NLOGE("netplay server: unknown TCP message %u, closing that connection", b[0]);
            return (size_t)-1;
        }
    }

    // --- UDP ---
    void sendInputs(const sockaddr_storage& to, socklen_t len, uint8_t kind, int p, uint32_t from, uint8_t lag) {
        uint8_t out[512]; size_t k = 5; uint8_t n = 0;
        for (auto it = inputs[p].find(from); it != inputs[p].end() && it->first == from + n && n < kMaxPerPacket; ++it, ++n) {
            w32(out + k, it->first); w32(out + k + 4, it->second.keys); out[k + 8] = it->second.plugin; k += 9;
        }
        out[0] = kind; out[1] = (uint8_t)p; out[2] = statusByte(); out[3] = lag; out[4] = n;
        udpSend(out, k, to, len);
    }
    // A player's input sent at its event count cc (UDP_SEND_KEY_INFO or a batch)
    void gotInput(int p, uint32_t cc, uint32_t keys, uint8_t plugin) {
        heard(p);
        if (pendingLower[p] && started[p] && cc == hi[p] + 1) {
            // taking a frame away: this input goes where the one before went, so it is dropped
            auto prev = inputs[p].find(hi[p] + (uint32_t)offsetOf(p, hi[p]));
            bool same = prev != inputs[p].end() && prev->second.keys == keys;
            if (same || --pendingLower[p] <= 0) {
                sched[p].push_back({cc, offsetOf(p, cc) - 1});
                pendingLower[p] = 0;
                NLOGI("netplay server: player %d input %u dropped for the lower delay%s", p + 1, cc, same ? "" : " (not a repeat)");
            }
        }
        if (!started[p] || (cc != hi[p] && cc - hi[p] < 0x80000000u)) { hi[p] = cc; started[p] = true; }
        uint32_t count = cc + (uint32_t)offsetOf(p, cc);
        if (inputs[p].count(count)) { ns[p].copies++; return; }
        ns[p].inputs++;
        put(p, count, {keys, plugin});
        // keep the last ~10 s
        while (!inputs[p].empty() && inputs[p].begin()->first + 600 < count) inputs[p].erase(inputs[p].begin());
    }
    void handleUdp(const uint8_t* d, ssize_t n, const sockaddr_storage& from, socklen_t len) {
        if (n < 1) return;
        switch (d[0]) {
        case UDP_JOEY_KEY_BATCH: {
            if (n < 3 || d[1] >= 4 || !fromPlayer(from, d[1])) return;
            addPeer(from, len);
            for (int i = 0, k = 3; i < d[2] && k + 9 <= n; i++, k += 9) gotInput(d[1], r32(d + k), r32(d + k + 4), d[k + 8]);
            break;
        }
        case UDP_SEND_KEY_INFO: {
            if (n < 11 || d[1] >= 4 || !fromPlayer(from, d[1])) return;
            addPeer(from, len);
            gotInput(d[1], r32(d + 2), r32(d + 6), d[10]);
            break;
        }
        case UDP_REQUEST_KEY_INFO: {
            if (n < 12 || d[1] >= 4) return;
            int p = d[1]; uint32_t id = r32(d + 2), count = r32(d + 6);
            // from a registered player's game only, and not far past what the player asked about has sent (H8: each
            // id and count asked for was kept): a game can't run past the inputs it's waiting for
            int me = playerOf(id);
            if (me < 0 || !fromPlayer(from, me)) return;
            if (!disconnected[p] && started[p] && count - hi[p] < 0x80000000u && count - hi[p] > 600) return;
            addPeer(from, len);
            lastAsked[p][id] = count;
            heard(me); ns[me].requests++;
            uint32_t lead = count;
            for (auto& [rid, c] : lastAsked[p]) if (c > lead && c - lead < 0x80000000u) lead = c;
            fillLeft(p, count);
            asked(p, count, me);
            if (adaptive && active(p) && !inputs[p].count(count)) {
                auto now = std::chrono::steady_clock::now();
                if (missing.size() >= kMaxMapped) missing.erase(missing.begin());
                tp askedAt = missing.emplace((uint64_t)p << 32 | count, now).first->second;
                // a slot nobody's input goes to, with later ones there: fill it with the input before (everyone gets
                // the same from the room, so no desync) and say what the slots look like
                auto after = inputs[p].upper_bound(count);
                if (after != inputs[p].end() && now - askedAt > std::chrono::seconds(1)) {
                    auto before = inputs[p].lower_bound(count);
                    Input copy = before != inputs[p].begin() ? std::prev(before)->second : Input{0, reg[p].plugin};
                    std::string s;
                    for (auto& [f, o] : sched[p]) s += " " + std::to_string(f) + ":" + std::to_string(o);
                    NLOGE("netplay server: player %d slot %u had no input for 1 s (next slot %u, newest input %u, delay from:%s), filled",
                          p + 1, count, after->first, hi[p], s.c_str());
                    put(p, count, copy);
                }
            }
            sendInputs(from, len, UDP_RECEIVE_KEY_INFO, p, count, (uint8_t)std::min<uint32_t>(lead - count, 255));
            break;
        }
        case UDP_PAUSE_NOTICE: {
            if (n < 3 || d[1] >= 4 || !fromPlayer(from, d[1])) return;
            // Joey64 2026-11-10: [.., seq32, ping ms u16, name length, name] adds the player's name and its own
            // measured ping; the room sends everyone the list (sendRoster)
            if (n >= 10) {
                int p = d[1];
                rosterPing[p] = (uint16_t)(d[7] << 8 | d[8]);
                size_t l = std::min<size_t>(d[9], (size_t)n - 10);
                std::string nm((const char*)d + 10, l);
                for (char& ch : nm) if (ch == '|' || ch == ';' || (unsigned char)ch < 32) ch = ' ';
                if (nm != rosterName[p]) { rosterName[p] = nm; NLOGI("netplay server: player %d is %s", p + 1, nm.c_str()); }
                rosterAddr[p] = from; rosterAddrLen[p] = len;
            }
            uint8_t bit = (uint8_t)(1 << d[1]), was = pausedMask;
            if (d[2] && !disconnected[d[1]]) pausedMask |= bit; else pausedMask &= (uint8_t)~bit;
            if (was != pausedMask) NLOGI("netplay server: player %d %s", d[1] + 1, d[2] ? "paused" : "resumed");
            // with a sequence number (7 bytes) it is also a ping: sent straight back, so the app can time the network
            if (n >= 7) udpSend(d, 7, from, len);
            break;
        }
        case UDP_SYNC_DATA: {
            if (n < 5 || !fromAnyPlayer(from)) return;
            uint32_t vi = r32(d + 1);
            auto it = sync.find(vi);
            if (it == sync.end()) { sync[vi].assign(d + 5, d + n); syncReports[vi] = 1; }
            else if (it->second.size() != (size_t)(n - 5) || memcmp(it->second.data(), d + 5, n - 5) != 0) {
                if (!(status & 1)) NLOGE("netplay server: players de-synced at VI %u", vi);
                status |= 1;
            } else if (++syncReports[vi] == registered()) {
                // every player's CP0 matched at this VI: log the first and then every 6th (once a minute at 60 VI/s)
                if (syncMatched++ % 6 == 0) NLOGI("netplay server: in sync at VI %u (%d checks matched so far)", vi, syncMatched);
            }
            while (sync.size() > 8) { syncReports.erase(sync.begin()->first); sync.erase(sync.begin()); }
            break;
        }
        default: break;
        }
    }

    void loop() {
        std::vector<pollfd> fds;
        std::vector<uint8_t> buf(65536);
        while (g_running) {
            fds.clear();
            fds.push_back({g_wake[0], POLLIN, 0});
            fds.push_back({tcp, POLLIN, 0});
            fds.push_back({udp, POLLIN, 0});
            for (auto& c : clients) fds.push_back({c.fd, (short)(POLLIN | (c.out.size() > c.outAt ? POLLOUT : 0)), 0});
            int r = poll(fds.data(), fds.size(), late.empty() ? 100 : 1);
            sendLate();
            if (r < 0 && errno != EINTR) break;
            if (fds[0].revents) break;
            if (fds[1].revents & POLLIN) {
                sockaddr_storage from{}; socklen_t flen = sizeof from;
                int fd = accept(tcp, (sockaddr*)&from, &flen);
                if (fd >= 0 && clients.size() >= kMaxClients) {
                    NLOGE("netplay server: a connection turned away: %zu already", clients.size());
                    close(fd);
                } else if (fd >= 0) {
                    const int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                    // never waits in send() (H7): a player that stops reading can't hold the room thread
                    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
                    Client c; c.fd = fd; c.at = std::chrono::steady_clock::now(); c.addr = from;
                    if (from.ss_family == AF_INET)
                        c.local = ntohl(((const sockaddr_in&)from).sin_addr.s_addr) == INADDR_LOOPBACK;
                    else if (from.ss_family == AF_INET6) {
                        const auto& a6 = ((const sockaddr_in6&)from).sin6_addr;
                        c.local = IN6_IS_ADDR_LOOPBACK(&a6) ||
                                  (IN6_IS_ADDR_V4MAPPED(&a6) && a6.s6_addr[12] == 127 && a6.s6_addr[15] == 1);
                    }
                    clients.push_back(c);
                    NLOGI("netplay server: a player connected (%zu)", clients.size());
                }
            }
            if (fds[2].revents & POLLIN) {
                for (;;) {
                    sockaddr_storage from; socklen_t len = sizeof from;
                    ssize_t n = recvfrom(udp, buf.data(), buf.size(), MSG_DONTWAIT, (sockaddr*)&from, &len);
                    if (n < 0) break;
                    handleUdp(buf.data(), n, from, len);
                }
            }
            // Clients are only removed after this pass, so fds[i + 3] stays client i's (code audit 3, p5 L7); one accepted
            // in this pass has no pollfd yet.
            auto now = std::chrono::steady_clock::now();
            for (size_t i = 0; i < clients.size(); i++) {
                Client& c = clients[i];
                short ev = i + 3 < fds.size() ? fds[i + 3].revents : 0;
                if (ev & POLLOUT) flush(c);
                if (ev & (POLLIN | POLLHUP | POLLERR)) {
                    ssize_t n = recv(c.fd, buf.data(), buf.size(), MSG_DONTWAIT);
                    if (n > 0) c.in.insert(c.in.end(), buf.begin(), buf.begin() + n);
                    else if (n == 0 || (errno != EAGAIN && errno != EINTR)) c.drop = true;
                }
                while (!c.drop) {
                    size_t used = handleTcp(c);
                    if (used == (size_t)-1) { c.drop = true; break; }
                    if (!used) break;
                    c.in.erase(c.in.begin(), c.in.begin() + (long)used);
                }
                // what's left must fit (H8), a save from player 1's game on top
                if (c.in.size() > kMaxIn + (feeds(c) ? kMaxSave + kMaxExt + 8 : 0)) {
                    NLOGE("netplay server: %zu bytes from a connection that aren't a message, closed", c.in.size());
                    c.drop = true;
                }
                if (!c.drop && !c.regId && !c.local && now - c.at > std::chrono::seconds(kRegisterSecs)) {
                    NLOGE("netplay server: a connection didn't register in %d s, closed", kRegisterSecs);
                    c.drop = true;
                }
                if (!c.drop) answerPending(c);
            }
            for (size_t i = 0; i < clients.size();) {
                if (!clients[i].drop) { i++; continue; }
                markLeft(playerOf(clients[i].regId));
                close(clients[i].fd);
                clients.erase(clients.begin() + (long)i);
            }
            g_status = statusByte();
            logNet();
            sendRoster();
            adapt();
            g_delay = delay;
        }
        for (auto& c : clients) close(c.fd);
        clients.clear();
    }
};

Room* g_room = nullptr;

int openSocket(int type, int port) {
    int fd = socket(AF_INET6, type, 0);
    bool v6 = fd >= 0;
    if (!v6) fd = socket(AF_INET, type, 0);
    if (fd < 0) return -1;
    const int one = 1, zero = 0;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    int ok;
    if (v6) {
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof zero); // IPv4 too
        sockaddr_in6 a{}; a.sin6_family = AF_INET6; a.sin6_port = htons((uint16_t)port); a.sin6_addr = in6addr_any;
        ok = bind(fd, (sockaddr*)&a, sizeof a);
    } else {
        sockaddr_in a{}; a.sin_family = AF_INET; a.sin_port = htons((uint16_t)port); a.sin_addr.s_addr = INADDR_ANY;
        ok = bind(fd, (sockaddr*)&a, sizeof a);
    }
    if (ok != 0 || (type == SOCK_STREAM && listen(fd, 8) != 0)) { close(fd); return -1; }
    return fd;
}

} // namespace

namespace {
void stopLocked();
}

bool start(int port, int players, int delay) {
    std::lock_guard<std::mutex> life(g_life);
    if (g_running) stopLocked();
    auto* room = new Room();
    room->players = players < 1 ? 1 : players > 4 ? 4 : players;
    room->adaptive = delay < 0;
    room->delay = room->adaptive ? Room::kMinDelay : delay > 30 ? 30 : delay;
    g_delay = room->delay;
    if (const char* s = getenv("N64_NETPLAY_SIM")) {
        sscanf(s, "%d%*[^0-9]%d%*[^0-9]%d", &room->simDelay, &room->simJitter, &room->simLoss);
        room->simLoss = std::clamp(room->simLoss, 0, 90);
        NLOGI("netplay server: simulated internet: %d ms late, up to %d ms more, %d%% lost", room->simDelay, room->simJitter, room->simLoss);
    }
    room->tcp = openSocket(SOCK_STREAM, port);
    room->udp = openSocket(SOCK_DGRAM, port);
    if (room->tcp < 0 || room->udp < 0 || pipe(g_wake) != 0) {
        NLOGE("netplay server: could not open port %d", port);
        if (room->tcp >= 0) close(room->tcp);
        if (room->udp >= 0) close(room->udp);
        delete room;
        return false;
    }
    g_room = room;
    g_regMask = 0; g_status = 0;
    g_running = true;
    g_thread = std::thread([room] { room->loop(); });
    NLOGI("netplay server: room open on port %d for %d players, input delay %d%s", port, room->players, room->delay,
          room->adaptive ? " (adaptive)" : "");
    // the port on the router too, so players outside the home network can join (dev: N64_NETPLAY_UPNP=0 skips it)
    { const char* u = getenv("N64_NETPLAY_UPNP"); if (!u || strcmp(u, "0") != 0) Upnp::open(port); }
    return true;
}

void stop() {
    std::lock_guard<std::mutex> life(g_life);
    stopLocked();
}

namespace {
void stopLocked() {
    if (!g_running.exchange(false)) return;
    if (g_wake[1] >= 0) { char c = 0; (void)!write(g_wake[1], &c, 1); }
    if (g_thread.joinable()) g_thread.join();
    close(g_room->tcp); close(g_room->udp);
    close(g_wake[0]); close(g_wake[1]); g_wake[0] = g_wake[1] = -1;
    delete g_room; g_room = nullptr;
    Upnp::close();
    NLOGI("netplay server: room closed");
}
} // namespace

bool running() { return g_running; }
uint8_t registeredMask() { return g_regMask; }
uint8_t status() { return g_status; }
int inputDelay() { return g_running ? g_delay.load() : 0; }

} // namespace NetplayServer
