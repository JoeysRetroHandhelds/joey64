// RetroAchievements in the native host (plan Phase 7). See achievements.h.
//
// rc_client owns the logic: sign-in, identifying the game by hash, evaluating achievements against
// RDRAM every frame, queueing unlocks it couldn't send and retrying them. Joey64 gives it three
// things: memory reads (the core's RDRAM, read as RetroArch and Mupen64Plus AE give it), server
// calls (RaBridge.serverCall in Kotlin, which answers through serverResponse), and an event handler
// (RaBridge.onEvent, as JSON). Hardcore is off: until RetroAchievements approves Joey64, the server
// keeps its unlocks as softcore anyway.
#include "achievements.h"

#include <jni.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <algorithm>
#include <string>

#include "joeylog.h"

extern "C" {
#include "rc_client.h"

// Java strings from rcheevos text (titles, descriptions, JSON). NewStringUTF takes modified UTF-8 only: a 4-byte
// sequence (an emoji in an achievement title) aborts the app under CheckJNI (debug builds). This builds UTF-16
// instead: 4-byte sequences become surrogate pairs, broken bytes U+FFFD.
static jstring jstr(JNIEnv* env, const char* s) {
    std::u16string out;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s ? s : "");
    while (*p) {
        uint32_t c = *p;
        int extra = c < 0x80 ? 0 : (c >> 5) == 6 ? 1 : (c >> 4) == 14 ? 2 : (c >> 3) == 30 ? 3 : -1;
        if (extra < 0) { out.push_back(0xFFFD); ++p; continue; }
        c &= extra == 0 ? 0x7F : extra == 1 ? 0x1F : extra == 2 ? 0x0F : 0x07;
        int k = 1;
        for (; k <= extra; ++k) { if ((p[k] & 0xC0) != 0x80) break; c = (c << 6) | (p[k] & 0x3F); }
        if (k <= extra) { out.push_back(0xFFFD); p += k; continue; }
        p += extra + 1;
        if (c >= 0x10000) { c -= 0x10000; out.push_back(char16_t(0xD800 + (c >> 10))); out.push_back(char16_t(0xDC00 + (c & 0x3FF))); }
        else out.push_back(char16_t(c));
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), (jsize)out.size());
}
#include "rc_api_request.h"
#include "rhash/md5.h"
#include "rc_api_runtime.h"
}

#define LOG_TAG "joey64ra"
#define RLOGI(...) joeyLog(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define RLOGE(...) joeyLog(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
jclass g_bridge = nullptr;           // RaBridge, a global ref
jmethodID g_serverCall = nullptr;    // static void serverCall(String url, String post, long handle)
jmethodID g_onEvent = nullptr;       // static void onEvent(String json)

rc_client_t* g_client = nullptr;
std::mutex g_ramMutex;               // the RDRAM pointer changes only at game load / unload
uint8_t* g_ram = nullptr;
size_t g_ramSize = 0;
char g_hash[33] = "";
std::mutex g_hostMutex;
std::string g_host;                  // what setHost gave rc_client, for awardRequest
std::atomic<bool> g_gameWanted{false};
std::atomic<bool> g_enabled{true};     // Settings › RetroAchievements › Achievements

struct Env {
    JNIEnv* env = nullptr;
    bool attached = false;
    Env() {
        if (g_vm && g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            g_vm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
    }
    ~Env() { if (attached) g_vm->DetachCurrentThread(); }
};

// --- JSON for the events (small, flat; strings escaped) -------------------------------------------
struct Json {
    std::string s = "{";
    void sep() { if (s.size() > 1) s += ','; }
    Json& str(const char* k, const char* v) {
        sep(); s += '"'; s += k; s += "\":\"";
        for (const char* p = v ? v : ""; *p; p++) {
            unsigned char c = (unsigned char)*p;
            if (c == '"' || c == '\\') { s += '\\'; s += (char)c; }
            else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); s += b; }
            else s += (char)c;
        }
        s += '"';
        return *this;
    }
    Json& num(const char* k, double v) { sep(); char b[64]; snprintf(b, sizeof b, "\"%s\":%.6g", k, v); s += b; return *this; }
    Json& boolean(const char* k, bool v) { sep(); s += '"'; s += k; s += "\":"; s += v ? "true" : "false"; return *this; }
    Json& raw(const char* k, const std::string& v) { sep(); s += '"'; s += k; s += "\":"; s += v; return *this; }
    std::string done() { return s + "}"; }
};

std::string achievementJson(const rc_client_achievement_t* a) {
    Json j;
    j.num("id", a->id).str("title", a->title).str("description", a->description).num("points", a->points)
        .str("badge", a->badge_url).str("badgeLocked", a->badge_locked_url).str("progress", a->measured_progress)
        .num("percent", a->measured_percent).num("rarity", a->rarity).num("rarityHardcore", a->rarity_hardcore)
        .num("unlockTime", (double)a->unlock_time).num("state", a->state).num("category", a->category)
        .num("bucket", a->bucket).num("unlocked", a->unlocked).num("type", a->type);
    return j.done();
}

std::string gameJson() {
    Json j;
    const rc_client_game_t* g = g_client ? rc_client_get_game_info(g_client) : nullptr;
    if (!g) return "null";
    rc_client_user_game_summary_t sum{};
    rc_client_get_user_game_summary(g_client, &sum);
    j.num("id", g->id).str("title", g->title).str("hash", g->hash).str("badge", g->badge_url)
        .num("achievements", sum.num_core_achievements).num("unlocked", sum.num_unlocked_achievements)
        .num("points", sum.points_core).num("pointsUnlocked", sum.points_unlocked)
        .num("beaten", (double)sum.beaten_time).num("completed", (double)sum.completed_time);
    return j.done();
}

std::string userJson() {
    const rc_client_user_t* u = g_client ? rc_client_get_user_info(g_client) : nullptr;
    if (!u) return "null";
    Json j;
    // The token goes to Kotlin once, to be kept for the next sign-in; it's never logged.
    j.str("user", u->username).str("display", u->display_name).str("token", u->token)
        .num("score", u->score).num("softcore", u->score_softcore).str("avatar", u->avatar_url);
    return j.done();
}

void emit(const std::string& json) {
    if (!g_bridge || !g_onEvent) return;
    Env e;
    if (!e.env) return;
    jstring s = jstr(e.env, json.c_str());
    e.env->CallStaticVoidMethod(g_bridge, g_onEvent, s);
    if (e.env->ExceptionCheck()) { e.env->ExceptionDescribe(); e.env->ExceptionClear(); }
    e.env->DeleteLocalRef(s);
}

// --- rc_client callbacks ------------------------------------------------------------------------
uint32_t readMemory(uint32_t address, uint8_t* buffer, uint32_t n, rc_client_t*) {
    std::lock_guard<std::mutex> lk(g_ramMutex);
    if (!g_ram || address >= g_ramSize) return 0;
    uint32_t count = (uint32_t)std::min<size_t>(n, g_ramSize - address);
    memcpy(buffer, g_ram + address, count);
    return count;
}

struct Pending { rc_client_server_callback_t cb; void* data; };

void serverCall(const rc_api_request_t* req, rc_client_server_callback_t cb, void* data, rc_client_t*) {
    if (!g_bridge || !g_serverCall) {
        rc_api_server_response_t r{};
        r.http_status_code = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
        cb(&r, data);
        return;
    }
    Env e;
    auto* p = new Pending{cb, data};
    jstring url = jstr(e.env, req->url);
    jstring post = req->post_data ? jstr(e.env, req->post_data) : nullptr;
    e.env->CallStaticVoidMethod(g_bridge, g_serverCall, url, post, (jlong)(intptr_t)p);
    if (e.env->ExceptionCheck()) { e.env->ExceptionDescribe(); e.env->ExceptionClear(); }
    e.env->DeleteLocalRef(url);
    if (post) e.env->DeleteLocalRef(post);
}

void logMessage(const char* msg, const rc_client_t*) { RLOGI("%s", msg); }

const char* eventName(uint32_t t) {
    switch (t) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: return "unlocked";
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED: return "leaderboardStarted";
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED: return "leaderboardFailed";
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED: return "leaderboardSubmitted";
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW: return "challengeShow";
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE: return "challengeHide";
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW: return "progressShow";
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE: return "progressHide";
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE: return "progressUpdate";
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW: return "trackerShow";
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE: return "trackerHide";
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE: return "trackerUpdate";
        case RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD: return "scoreboard";
        case RC_CLIENT_EVENT_RESET: return "reset";
        case RC_CLIENT_EVENT_GAME_COMPLETED: return "gameCompleted";
        case RC_CLIENT_EVENT_SERVER_ERROR: return "serverError";
        case RC_CLIENT_EVENT_DISCONNECTED: return "disconnected";
        case RC_CLIENT_EVENT_RECONNECTED: return "reconnected";
        case RC_CLIENT_EVENT_SUBSET_COMPLETED: return "subsetCompleted";
        default: return "other";
    }
}

void onEvent(const rc_client_event_t* ev, rc_client_t*) {
    Json j;
    j.str("event", eventName(ev->type));
    if (ev->achievement) j.raw("achievement", achievementJson(ev->achievement));
    if (ev->leaderboard) {
        Json l;
        l.num("id", ev->leaderboard->id).str("title", ev->leaderboard->title)
            .str("description", ev->leaderboard->description).str("value", ev->leaderboard->tracker_value);
        j.raw("leaderboard", l.done());
    }
    if (ev->leaderboard_tracker) {
        Json t;
        t.num("id", ev->leaderboard_tracker->id).str("display", ev->leaderboard_tracker->display);
        j.raw("tracker", t.done());
    }
    if (ev->leaderboard_scoreboard) {
        Json s;
        s.str("submitted", ev->leaderboard_scoreboard->submitted_score).str("best", ev->leaderboard_scoreboard->best_score)
            .num("rank", ev->leaderboard_scoreboard->new_rank).num("entries", ev->leaderboard_scoreboard->num_entries);
        j.raw("scoreboard", s.done());
    }
    if (ev->server_error) j.str("api", ev->server_error->api).str("error", ev->server_error->error_message);
    if (ev->subset) j.str("subset", ev->subset->title);
    if (ev->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED || ev->type == RC_CLIENT_EVENT_GAME_COMPLETED)
        j.raw("game", gameJson());
    if (ev->type == RC_CLIENT_EVENT_RESET) {
        // Only sent when hardcore is turned on mid-game; Joey64 has no hardcore yet. Reset rc_client
        // so it doesn't wait for one.
        rc_client_reset(g_client);
    }
    emit(j.done());
}

void loginDone(int result, const char* error, rc_client_t*, void*) {
    Json j;
    j.str("event", "login").num("result", result).str("error", result == RC_OK ? "" : (error ? error : rc_error_str(result)));
    if (result == RC_OK) j.raw("user", userJson());
    RLOGI("sign-in: %s", result == RC_OK ? "ok" : (error ? error : rc_error_str(result)));
    emit(j.done());
}

void gameDone(int result, const char* error, rc_client_t*, void*) {
    Json j;
    j.str("event", "gameLoaded").num("result", result).str("hash", g_hash)
        .str("error", result == RC_OK ? "" : (error ? error : rc_error_str(result)));
    if (result == RC_OK) j.raw("game", gameJson());
    RLOGI("game %s: %s", g_hash, result == RC_OK ? "identified" : (error ? error : rc_error_str(result)));
    emit(j.done());
}

void ensureClient() {
    if (g_client) return;
    g_client = rc_client_create(readMemory, serverCall);
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, logMessage);
    rc_client_set_event_handler(g_client, onEvent);
    rc_client_set_hardcore_enabled(g_client, 0); // softcore only until RetroAchievements approves Joey64
    rc_client_set_unofficial_enabled(g_client, 0);
    rc_client_set_encore_mode_enabled(g_client, 0);
}

// The RetroAchievements N64 hash (rc_hash_n64): MD5 of the ROM in .z64 (big-endian) order, the first
// 64 MiB at most. .v64 swaps each 16-bit pair, .n64 each 32-bit word.
void hashRom(const uint8_t* rom, size_t size, char out[33]) {
    out[0] = 0;
    if (size < 4) return;
    size = std::min<size_t>(size, 64u * 1024 * 1024);
    md5_state_t md5;
    md5_init(&md5);
    const bool v64 = rom[0] == 0x37 && rom[1] == 0x80, n64 = rom[0] == 0x40 && rom[1] == 0x12;
    uint8_t buf[65536];
    for (size_t off = 0; off < size; off += sizeof buf) {
        size_t len = std::min(sizeof buf, size - off);
        memcpy(buf, rom + off, len);
        if (v64) for (size_t i = 0; i + 1 < len; i += 2) std::swap(buf[i], buf[i + 1]);
        else if (n64) for (size_t i = 0; i + 3 < len; i += 4) { std::swap(buf[i], buf[i + 3]); std::swap(buf[i + 1], buf[i + 2]); }
        md5_append(&md5, buf, (int)len);
    }
    md5_byte_t d[16];
    md5_finish(&md5, d);
    for (int i = 0; i < 16; i++) snprintf(out + i * 2, 3, "%02x", d[i]);
}

} // namespace

namespace Cheevos {

void gameLoaded(const uint8_t* rom, size_t romSize, uint8_t* ram, size_t ramSize) {
    hashRom(rom, romSize, g_hash);
    {
        std::lock_guard<std::mutex> lk(g_ramMutex);
        g_ram = ram; g_ramSize = ramSize;
    }
    RLOGI("game hash %s, RDRAM %zu bytes", g_hash, ramSize);
    if (!g_client || !g_hash[0]) return;
    if (!g_enabled) { RLOGI("achievements are off in Settings"); return; }
    const rc_client_user_t* u = rc_client_get_user_info(g_client);
    // Signed in, or a sign-in under way (rc_client waits for it before loading).
    if (!u && !g_gameWanted) return;
    rc_client_begin_load_game(g_client, g_hash, gameDone, nullptr);
}

void gameUnloaded() {
    if (g_client) rc_client_unload_game(g_client);
    std::lock_guard<std::mutex> lk(g_ramMutex);
    g_ram = nullptr; g_ramSize = 0;
}

void doFrame() { if (g_client) rc_client_do_frame(g_client); }
void idle() { if (g_client) rc_client_idle(g_client); }
void reset() { if (g_client) rc_client_reset(g_client); }
void stateLoaded() { if (g_client) rc_client_deserialize_progress_sized(g_client, nullptr, 0); }
const char* lastHash() { return g_hash; }

} // namespace Cheevos

// --- JNI: com.joeysretro.joey64.achievements.RaBridge -----------------------------------------------
#define RAJNI(name) Java_com_joeysretro_joey64_achievements_RaBridge_##name

extern "C" {

JNIEXPORT void JNICALL RAJNI(nativeInit)(JNIEnv* env, jclass cls) {
    if (g_bridge) return;
    env->GetJavaVM(&g_vm);
    g_bridge = static_cast<jclass>(env->NewGlobalRef(cls));
    g_serverCall = env->GetStaticMethodID(cls, "serverCall", "(Ljava/lang/String;Ljava/lang/String;J)V");
    g_onEvent = env->GetStaticMethodID(cls, "onEvent", "(Ljava/lang/String;)V");
    ensureClient();
}

// The User-Agent clause rcheevos asks clients to send ("rcheevos/12.5.0").
JNIEXPORT jstring JNICALL RAJNI(userAgentClause)(JNIEnv* env, jclass) {
    ensureClient();
    char buf[64] = "";
    rc_client_get_user_agent_clause(g_client, buf, sizeof buf);
    return jstr(env, buf);
}

JNIEXPORT void JNICALL RAJNI(serverResponse)(JNIEnv* env, jclass, jlong handle, jint status, jbyteArray body) {
    auto* p = reinterpret_cast<Pending*>((intptr_t)handle);
    if (!p) return;
    rc_api_server_response_t r{};
    r.http_status_code = status;
    jbyte* bytes = nullptr;
    jsize len = 0;
    if (body) { len = env->GetArrayLength(body); bytes = env->GetByteArrayElements(body, nullptr); }
    r.body = reinterpret_cast<const char*>(bytes);
    r.body_length = (size_t)len;
    p->cb(&r, p->data);
    if (bytes) env->ReleaseByteArrayElements(body, bytes, JNI_ABORT);
    delete p;
}

JNIEXPORT void JNICALL RAJNI(loginWithPassword)(JNIEnv* env, jclass, jstring user, jstring password) {
    ensureClient();
    const char* u = env->GetStringUTFChars(user, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    g_gameWanted = true;
    rc_client_begin_login_with_password(g_client, u, pw, loginDone, nullptr);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(user, u);
}

JNIEXPORT void JNICALL RAJNI(loginWithToken)(JNIEnv* env, jclass, jstring user, jstring token) {
    ensureClient();
    const char* u = env->GetStringUTFChars(user, nullptr);
    const char* t = env->GetStringUTFChars(token, nullptr);
    g_gameWanted = true;
    rc_client_begin_login_with_token(g_client, u, t, loginDone, nullptr);
    env->ReleaseStringUTFChars(token, t);
    env->ReleaseStringUTFChars(user, u);
}

JNIEXPORT void JNICALL RAJNI(logout)(JNIEnv*, jclass) {
    g_gameWanted = false;
    if (g_client) rc_client_logout(g_client);
}

// Settings › RetroAchievements. Encore and Unofficial apply from the next game load, Spectator
// at once (rc_client's own rules); Achievements Off skips identifying the next game.
JNIEXPORT void JNICALL RAJNI(setOptions)(JNIEnv*, jclass, jboolean enabled, jboolean encore, jboolean spectator, jboolean unofficial) {
    ensureClient();
    g_enabled = enabled;
    rc_client_set_encore_mode_enabled(g_client, encore);
    rc_client_set_spectator_mode_enabled(g_client, spectator);
    rc_client_set_unofficial_enabled(g_client, unofficial);
}

// The RetroAchievements server, or (RAOfflineProxy) a local one; null or empty = the real server.
// RaBridge checks the address; this only applies it (rc_client uses it from the next request).
JNIEXPORT void JNICALL RAJNI(setHost)(JNIEnv* env, jclass, jstring host) {
    ensureClient();
    const char* h = host ? env->GetStringUTFChars(host, nullptr) : nullptr;
    rc_client_set_host(g_client, h && *h ? h : nullptr);
    { std::lock_guard<std::mutex> l(g_hostMutex); g_host = h ? h : ""; }
    if (h) env->ReleaseStringUTFChars(host, h);
}

JNIEXPORT jstring JNICALL RAJNI(gameInfo)(JNIEnv* env, jclass) {
    return jstr(env, gameJson().c_str());
}

// Every achievement of the loaded game, grouped as RetroArch shows them (RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS).
JNIEXPORT jstring JNICALL RAJNI(achievementList)(JNIEnv* env, jclass) {
    std::string out = "[";
    if (g_client && rc_client_is_game_loaded(g_client)) {
        rc_client_achievement_list_t* list = rc_client_create_achievement_list(g_client,
            RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
        for (uint32_t b = 0; list && b < list->num_buckets; b++) {
            const auto& bucket = list->buckets[b];
            for (uint32_t i = 0; i < bucket.num_achievements; i++) {
                if (out.size() > 1) out += ',';
                std::string a = achievementJson(bucket.achievements[i]);
                a.pop_back();
                Json extra; extra.str("section", bucket.label).num("sectionType", bucket.bucket_type);
                out += a + "," + extra.done().substr(1);
            }
        }
        if (list) rc_client_destroy_achievement_list(list);
    }
    out += "]";
    return jstr(env, out.c_str());
}

// The offline queue (RaUnlockQueue): an unlock rc_client couldn't send before its game was left,
// rebuilt for the signed-in account with how long ago it happened (rcheevos' delayed unlock, the
// "o" field). {"url":..,"post":..}, or "" when nobody is signed in. The queue keeps no sign-in key.
JNIEXPORT jstring JNICALL RAJNI(awardRequest)(JNIEnv* env, jclass, jint id, jstring hash, jint secondsSince) {
    std::string out;
    const rc_client_user_t* user = g_client ? rc_client_get_user_info(g_client) : nullptr;
    if (user && user->username && user->token && id > 0) {
        const char* h = hash ? env->GetStringUTFChars(hash, nullptr) : nullptr;
        rc_api_award_achievement_request_t params{};
        params.username = user->username;
        params.api_token = user->token;
        params.achievement_id = (uint32_t)id;
        params.hardcore = 0; // softcore only
        params.game_hash = h;
        params.seconds_since_unlock = (uint32_t)std::max(secondsSince, 1);
        std::string hostName;
        { std::lock_guard<std::mutex> l(g_hostMutex); hostName = g_host; }
        rc_api_host_t apiHost{hostName.empty() ? nullptr : hostName.c_str(), nullptr};
        rc_api_request_t req{};
        if (rc_api_init_award_achievement_request_hosted(&req, &params, &apiHost) == RC_OK) {
            Json j; j.str("url", req.url).str("post", req.post_data ? req.post_data : "");
            out = j.done();
        }
        rc_api_destroy_request(&req);
        if (h) env->ReleaseStringUTFChars(hash, h);
    }
    return jstr(env, out.c_str());
}

// Whether rc_client has a game loaded (it then retries its own unlocks).
JNIEXPORT jboolean JNICALL RAJNI(gameLoaded)(JNIEnv*, jclass) {
    return g_client && rc_client_is_game_loaded(g_client);
}

JNIEXPORT jstring JNICALL RAJNI(lastHash)(JNIEnv* env, jclass) {
    return jstr(env, g_hash);
}

} // extern "C"
