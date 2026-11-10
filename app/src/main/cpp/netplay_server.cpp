// Netplay room server: see netplay_server.h. GPL-3.0-or-later.
#include "netplay_server.h"
#include "joeylog.h"

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
                 UDP_PAUSE_NOTICE = 5 };
enum : uint8_t { TCP_SEND_SAVE = 1, TCP_RECEIVE_SAVE = 2, TCP_SEND_SETTINGS = 3, TCP_RECEIVE_SETTINGS = 4,
                 TCP_REGISTER_PLAYER = 5, TCP_GET_REGISTRATION = 6, TCP_DISCONNECT_NOTICE = 7 };
constexpr int kSettingsSize = 24;     // SETTINGS_SIZE: six 32-bit settings
constexpr int kMaxPerPacket = 56;     // (512 - 5) / 9 input events in one reply
constexpr uint8_t kBufferTarget = 2;  // the client's local buffer above which it may catch up (fast-forward)

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
};
struct Peer { sockaddr_storage addr; socklen_t len; };

std::atomic<bool> g_running{false};
std::atomic<uint8_t> g_regMask{0}, g_status{0};
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

    void sendAll(int fd, const uint8_t* d, size_t n) {
        size_t done = 0;
        while (done < n) {
            ssize_t k = send(fd, d + done, n - done, MSG_NOSIGNAL);
            if (k < 0 && errno == EINTR) continue;
            if (k <= 0) return;
            done += (size_t)k;
        }
    }
    void addPeer(const sockaddr_storage& a, socklen_t len) {
        for (auto& p : peers) if (p.len == len && memcmp(&p.addr, &a, len) == 0) return;
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
    }

    // --- TCP ---
    void answerPending(Client& c) {
        if (c.waitingRegistration && (registered() >= players ||
                std::chrono::steady_clock::now() - c.regAskedAt > std::chrono::seconds(30))) {
            uint8_t out[24]; size_t k = 0;
            for (int i = 0; i < 4; i++) { w32(out + k, reg[i].id); k += 4; out[k++] = reg[i].plugin; out[k++] = reg[i].raw; }
            sendAll(c.fd, out, sizeof out);
            c.waitingRegistration = false;
            NLOGI("netplay server: registration sent to %08x (%d of %d players)", c.regId, registered(), players);
        }
        if (c.waitingSettings && haveSettings) {
            sendAll(c.fd, settings.data(), settings.size());
            c.waitingSettings = false;
        }
        for (size_t i = 0; i < c.waitingSaves.size();) {
            auto it = saves.find(c.waitingSaves[i]);
            if (it == saves.end()) { i++; continue; }
            sendAll(c.fd, it->second.data(), it->second.size());
            c.waitingSaves.erase(c.waitingSaves.begin() + i);
        }
    }
    // One whole message from the start of c.in, or 0 if more bytes are needed.
    size_t handleTcp(Client& c) {
        const auto& b = c.in;
        if (b.empty()) return 0;
        auto strEnd = [&](size_t from) -> long { for (size_t i = from; i < b.size(); i++) if (!b[i]) return (long)i; return -1; };
        switch (b[0]) {
        case TCP_REGISTER_PLAYER: {
            if (b.size() < 8) return 0;
            int p = b[1]; uint32_t id = r32(&b[4]);
            uint8_t ok = 0;
            if (p >= 0 && p < 4 && (reg[p].id == 0 || reg[p].id == id)) {
                reg[p] = {id, b[2], b[3]}; ok = 1; c.regId = id;
                uint8_t m = 0; for (int i = 0; i < 4; i++) if (reg[i].id) m |= 1 << i;
                g_regMask = m;
                // the first `delay` events of each player are blank, which is what gives the input delay
                for (uint32_t k = 0; k < (uint32_t)delay; k++) inputs[p][k] = {0, b[2]};
            }
            NLOGI("netplay server: player %d register %08x: %s", p + 1, id, ok ? "ok" : "taken");
            uint8_t out[2] = {ok, kBufferTarget};
            sendAll(c.fd, out, 2);
            return 8;
        }
        case TCP_GET_REGISTRATION:
            c.waitingRegistration = true; c.regAskedAt = std::chrono::steady_clock::now();
            return 1;
        case TCP_SEND_SETTINGS:
            if (b.size() < 1 + kSettingsSize) return 0;
            settings.assign(b.begin() + 1, b.begin() + 1 + kSettingsSize); haveSettings = true;
            return 1 + kSettingsSize;
        case TCP_RECEIVE_SETTINGS:
            c.waitingSettings = true;
            return 1;
        case TCP_SEND_SAVE: {
            long e = strEnd(1);
            if (e < 0 || b.size() < (size_t)e + 5) return 0;
            uint32_t size = r32(&b[e + 1]);
            if (b.size() < (size_t)e + 5 + size) return 0;
            std::string ext((const char*)&b[1]);
            saves[ext].assign(b.begin() + e + 5, b.begin() + e + 5 + size);
            NLOGI("netplay server: save .%s from player 1 (%u bytes)", ext.c_str(), size);
            return (size_t)e + 5 + size;
        }
        case TCP_RECEIVE_SAVE: {
            long e = strEnd(1);
            if (e < 0) return 0;
            c.waitingSaves.emplace_back((const char*)&b[1]);
            return (size_t)e + 1;
        }
        case TCP_DISCONNECT_NOTICE:
            if (b.size() < 5) return 0;
            markLeft(playerOf(r32(&b[1])));
            return 5;
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
    void handleUdp(const uint8_t* d, ssize_t n, const sockaddr_storage& from, socklen_t len) {
        if (n < 1) return;
        switch (d[0]) {
        case UDP_SEND_KEY_INFO: {
            if (n < 11 || d[1] >= 4) return;
            addPeer(from, len);
            int p = d[1]; uint32_t count = r32(d + 2) + (uint32_t)delay;
            heard(p);
            if (inputs[p].count(count)) { ns[p].copies++; return; }
            ns[p].inputs++;
            inputs[p][count] = {r32(d + 6), d[10]};
            // everyone gets it at once (the "gratuitous" reply), so nobody waits for a request round trip
            for (auto& peer : peers) sendInputs(peer.addr, peer.len, UDP_RECEIVE_KEY_INFO_GRATUITOUS, p, count, 0);
            // keep the last ~10 s
            while (!inputs[p].empty() && inputs[p].begin()->first + 600 < count) inputs[p].erase(inputs[p].begin());
            break;
        }
        case UDP_REQUEST_KEY_INFO: {
            if (n < 12 || d[1] >= 4) return;
            addPeer(from, len);
            int p = d[1]; uint32_t id = r32(d + 2), count = r32(d + 6);
            lastAsked[p][id] = count;
            { int me = playerOf(id); if (me >= 0) { heard(me); ns[me].requests++; } }
            uint32_t lead = count;
            for (auto& [rid, c] : lastAsked[p]) if (c > lead && c - lead < 0x80000000u) lead = c;
            fillLeft(p, count);
            sendInputs(from, len, UDP_RECEIVE_KEY_INFO, p, count, (uint8_t)std::min<uint32_t>(lead - count, 255));
            break;
        }
        case UDP_PAUSE_NOTICE: {
            if (n < 3 || d[1] >= 4) return;
            uint8_t bit = (uint8_t)(1 << d[1]), was = pausedMask;
            if (d[2] && !disconnected[d[1]]) pausedMask |= bit; else pausedMask &= (uint8_t)~bit;
            if (was != pausedMask) NLOGI("netplay server: player %d %s", d[1] + 1, d[2] ? "paused" : "resumed");
            // with a sequence number (7 bytes) it is also a ping: sent straight back, so the app can time the network
            if (n >= 7) udpSend(d, 7, from, len);
            break;
        }
        case UDP_SYNC_DATA: {
            if (n < 5) return;
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
            for (auto& c : clients) fds.push_back({c.fd, POLLIN, 0});
            int r = poll(fds.data(), fds.size(), late.empty() ? 100 : 1);
            sendLate();
            if (r < 0 && errno != EINTR) break;
            if (fds[0].revents) break;
            if (fds[1].revents & POLLIN) {
                int fd = accept(tcp, nullptr, nullptr);
                if (fd >= 0) {
                    const int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                    Client c; c.fd = fd; clients.push_back(c);
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
            for (size_t i = 0; i < clients.size(); i++) {
                Client& c = clients[i];
                bool closed = false;
                if (i + 3 < fds.size() && (fds[i + 3].revents & (POLLIN | POLLHUP | POLLERR))) {
                    ssize_t n = recv(c.fd, buf.data(), buf.size(), MSG_DONTWAIT);
                    if (n > 0) c.in.insert(c.in.end(), buf.begin(), buf.begin() + n);
                    else if (n == 0 || (errno != EAGAIN && errno != EINTR)) closed = true;
                }
                for (;;) {
                    size_t used = handleTcp(c);
                    if (used == (size_t)-1) { closed = true; break; }
                    if (!used) break;
                    c.in.erase(c.in.begin(), c.in.begin() + used);
                }
                if (!closed) answerPending(c);
                if (closed) {
                    markLeft(playerOf(c.regId));
                    close(c.fd);
                    clients.erase(clients.begin() + i); i--;
                }
            }
            g_status = statusByte();
            logNet();
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

bool start(int port, int players, int delay) {
    if (g_running) stop();
    auto* room = new Room();
    room->players = players < 1 ? 1 : players > 4 ? 4 : players;
    room->delay = delay < 0 ? 0 : delay > 30 ? 30 : delay;
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
    NLOGI("netplay server: room open on port %d for %d players, input delay %d", port, room->players, room->delay);
    return true;
}

void stop() {
    if (!g_running) return;
    g_running = false;
    if (g_wake[1] >= 0) { char c = 0; (void)!write(g_wake[1], &c, 1); }
    if (g_thread.joinable()) g_thread.join();
    close(g_room->tcp); close(g_room->udp);
    close(g_wake[0]); close(g_wake[1]); g_wake[0] = g_wake[1] = -1;
    delete g_room; g_room = nullptr;
    NLOGI("netplay server: room closed");
}

bool running() { return g_running; }
uint8_t registeredMask() { return g_regMask; }
uint8_t status() { return g_status; }

} // namespace NetplayServer
