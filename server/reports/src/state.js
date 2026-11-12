// POST /v1/state: a player's save state for a game on the wanted list (Joey 2026-11-12: the in-app popup asks once,
// "Yes" sends it; joey64-notes queue.md "Ask players for a state of games we need data for"). The file goes to R2,
// private: a state holds the game's memory, its code included, so it is never served back or shared; we pull states
// only for our own test runs (the widescreen finder, problem games). One D1 row per state for the counts.
//
// Body: multipart/form-data with md5 (the ROM's, 32 hex), kind ("gameplay" or "problem", as the wanted list says),
// app (version), state (a RetroArch state file, RZIP: the app wraps the ring's raw M64+SAVE data with StateFormat.wrap,
// java.util.zip only; Frontend 2026-11-12) and optionally save (the game's .srm, gzipped).
// Answers: 204 stored; 400 a bad field; 404 the game isn't wanted (don't send again); 410 we have enough of it (don't
// send again; the app tells them apart by the body "not wanted", so keep it); 413 too big; 429 with Retry-After (seconds) when a limit is reached; 503 the wanted list didn't load.
import { blockOf, limiterKeyOf, scramble } from "./address.js";
import { today } from "./store.js";

/** An RZIP state: 16.8 MB raw packs to about 2 MB (OoT's quit state 1.9 MB). */
export const MAX_STATE = 20 << 20;
/** The biggest cartridge save the core writes, gzipped or not (its .srm holds EEPROM, SRAM, FlashRAM and paks: 0x48800 bytes). */
export const MAX_SAVE = 512 << 10;
/** States kept a game unless the wanted list says otherwise. */
export const DEFAULT_ENOUGH = 10;
/** States one /24 or /64 can send in a day, and the whole server. */
export const BLOCK_DAY_STATES = 10;
export const DAY_STATES = 200;
/** All states kept, under R2's free 10 GB. */
export const MAX_TOTAL_BYTES = 8 * (1 << 30);
export const WANTED_URL = "https://raw.githubusercontent.com/JoeysRetroHandhelds/joey64-data/release/wanted/wanted.json";

const RZIP = [..."#RZIPv\x01#"].map((c) => c.charCodeAt(0));
const GZIP = [0x1f, 0x8b];
let cache = { at: 0, list: null };

/** The wanted list by md5: env.WANTED (tests) or the joey64-data release file, kept 10 minutes. */
async function wanted(env) {
  if (env.WANTED) return index(env.WANTED);
  if (cache.list && Date.now() - cache.at < 600_000) return cache.list;
  const res = await fetch(env.WANTED_URL || WANTED_URL, { cf: { cacheTtl: 600 } });
  if (!res.ok) throw new Error("wanted list " + res.status);
  cache = { at: Date.now(), list: index(await res.json()) };
  return cache.list;
}

function index(w) {
  const m = new Map();
  for (const g of (w && w.games) || []) if (/^[0-9a-f]{32}$/.test(g.md5)) m.set(g.md5, g);
  return m;
}

function secondsToMidnight() {
  const now = new Date();
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(60, Math.ceil((next - now.getTime()) / 1000));
}

export async function handleState(request, env, text) {
  if (request.method !== "POST") return text(405, "POST only");
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (env.PER_IP) {
    const { success } = await env.PER_IP.limit({ key: limiterKeyOf(ip) });
    if (!success) return text(429, "slow down", { "retry-after": "60" });
  }
  const length = Number(request.headers.get("content-length") || "0");
  if (length > MAX_STATE + MAX_SAVE + 64 * 1024) return text(413, "too big");
  if (!(request.headers.get("content-type") || "").startsWith("multipart/form-data")) return text(400, "multipart only");

  let form;
  try { form = await request.formData(); } catch { return text(400, "bad form"); }
  const md5 = String(form.get("md5") || "");
  const kind = String(form.get("kind") || "");
  const app = String(form.get("app") || "");
  const state = form.get("state");
  const save = form.get("save");
  if (!/^[0-9a-f]{32}$/.test(md5)) return text(400, "bad md5");
  if (kind !== "gameplay" && kind !== "problem") return text(400, "bad kind");
  if (!/^[0-9A-Za-z.+_-]{1,40}$/.test(app)) return text(400, "bad app");
  if (!state || typeof state === "string") return text(400, "no state");
  if (state.size > MAX_STATE) return text(413, "state too big");
  if (save && typeof save !== "string" && save.size > MAX_SAVE) return text(413, "save too big");
  if (save && typeof save === "string" && save.length) return text(400, "bad save");
  const head = new Uint8Array(await state.slice(0, RZIP.length).arrayBuffer());
  if (state.size < 1024 || RZIP.some((b, i) => head[i] !== b)) return text(400, "state isn't RZIP");
  if (save && typeof save !== "string") {
    const sh = new Uint8Array(await save.slice(0, 2).arrayBuffer());
    if (GZIP.some((b, i) => sh[i] !== b)) return text(400, "save isn't gzip");
  }

  let list;
  try { list = await wanted(env); } catch { return text(503, "wanted list unavailable", { "retry-after": "600" }); }
  const game = list.get(md5);
  if (!game) return text(404, "not wanted");
  if ((game.kind || "gameplay") !== kind) return text(400, "wrong kind");

  const enough = Number.isInteger(game.enough) && game.enough > 0 ? game.enough : DEFAULT_ENOUGH;
  const have = await env.DB.prepare("SELECT count(*) AS n FROM states WHERE md5 = ?").bind(md5).first("n");
  if (have >= enough) return text(410, "have enough");
  const total = await env.DB.prepare("SELECT coalesce(sum(state_bytes + save_bytes), 0) AS b FROM states").first("b");
  const saveBytes = save && typeof save !== "string" ? save.size : 0;
  if (total + state.size + saveBytes > MAX_TOTAL_BYTES) return text(429, "storage full", { "retry-after": "86400" });

  const day = new Date().toISOString().slice(0, 10);
  const t = await today(env.DB, day);
  // A state's address hash is only for its own day's count (the day's salt is deleted with it), so older ones go.
  await env.DB.prepare("UPDATE states SET who = NULL WHERE day < ? AND who IS NOT NULL").bind(day).run();
  const dayCount = await env.DB.prepare("SELECT count(*) AS n FROM states WHERE day = ?").bind(day).first("n");
  if (dayCount >= DAY_STATES) return text(429, "full for today", { "retry-after": String(secondsToMidnight()) });
  const who = await scramble(t.salt, blockOf(ip));
  const mine = await env.DB.prepare("SELECT count(*) AS n FROM states WHERE day = ? AND who = ?").bind(day, who).first("n");
  if (mine >= BLOCK_DAY_STATES) return text(429, "enough from this address today", { "retry-after": String(secondsToMidnight()) });

  const key = `states/${md5}/${day}-${crypto.randomUUID()}`;
  await env.STATES.put(key + ".state", state.stream(), { httpMetadata: { contentType: "application/octet-stream" }, customMetadata: { md5, kind, app } });
  if (saveBytes) await env.STATES.put(key + ".srm.gz", save.stream(), { httpMetadata: { contentType: "application/gzip" }, customMetadata: { md5, kind, app } });
  try {
    await env.DB.prepare("INSERT INTO states (day, md5, kind, app, state_bytes, save_bytes, who, r2_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .bind(day, md5, kind, app, state.size, saveBytes, who, key).run();
  } catch (e) {
    // No row, no file: the count would miss it otherwise.
    await env.STATES.delete([key + ".state", key + ".srm.gz"]);
    throw e;
  }
  return text(204, null);
}
