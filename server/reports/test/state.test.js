import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { deflateSync } from "node:zlib";
import { BLOCK_DAY_STATES, DEFAULT_ENOUGH, MAX_SAVE } from "../src/state.js";

const MD5 = "9feee5da895fb2e115bb15d1c4a9f906";
const OTHER = "0123456789abcdef0123456789abcdef";
const WANTED = { version: 1, games: [{ md5: MD5, kind: "gameplay", need: "a race", enough: 3 }, { md5: OTHER, kind: "problem", need: "x" }] };

/** Enough of D1 for the state route: the states table and the day's row (salt). */
function fakeDb() {
  const t = { states: [], days: {} };
  const prep = (sql) => ({
    sql, args: [],
    bind(...a) { this.args = a; return this; },
    async first(col) {
      const a = this.args;
      let v;
      if (sql.startsWith("SELECT count(*) AS n FROM states WHERE md5")) v = { n: t.states.filter((s) => s.md5 === a[0]).length };
      else if (sql.startsWith("SELECT coalesce")) v = { b: t.states.reduce((x, s) => x + s.state_bytes + s.save_bytes, 0) };
      else if (sql.startsWith("SELECT count(*) AS n FROM states WHERE day = ? AND who")) v = { n: t.states.filter((s) => s.day === a[0] && s.who === a[1]).length };
      else if (sql.startsWith("SELECT count(*) AS n FROM states WHERE day")) v = { n: t.states.filter((s) => s.day === a[0]).length };
      else if (sql.startsWith("SELECT written, bytes, salt FROM days")) v = t.days[a[0]] ? { ...t.days[a[0]] } : null;
      else throw new Error("unexpected " + sql);
      return col ? v[col] : v;
    },
    async run() {
      const a = this.args;
      if (sql.startsWith("INSERT INTO states")) t.states.push({ day: a[0], md5: a[1], kind: a[2], app: a[3], state_bytes: a[4], save_bytes: a[5], who: a[6], key: a[7] });
      else if (sql.startsWith("UPDATE states SET who")) { for (const s of t.states) if (s.day < a[0]) s.who = null; }
      else if (sql.startsWith("INSERT INTO days")) { if (!t.days[a[0]]) t.days[a[0]] = { written: 0, bytes: 0, salt: a[1] }; }
      else if (sql.startsWith("DELETE FROM posters") || sql.startsWith("UPDATE days SET salt")) {}
      else throw new Error("unexpected " + sql);
    },
  });
  return { t, prepare: prep, async batch(list) { for (const s of list) await s.run(); } };
}

function fakeR2() {
  const files = new Map();
  return {
    files,
    async put(key, body) { files.set(key, new Uint8Array(await new Response(body).arrayBuffer())); },
    async delete(keys) { for (const k of [].concat(keys)) files.delete(k); },
  };
}

/**
 * A state as the app sends it: RZIP around RASTATE, whose MEM block starts with the core's M64+SAVE header (version,
 * then the ROM's MD5 in upper case). The first chunk is real zlib, padded to [pad] bytes of data; the header's
 * unpacked size says 16.8 MB, as a real one does. [mem] replaces the MEM block's start.
 */
function rz({ md5 = MD5, pad = 4096, mem = null, unpacked = 16_800_000 } = {}) {
  const enc = (s) => [...s].map((c) => c.charCodeAt(0));
  const core = mem || new Uint8Array([...enc("M64+SAVE"), 0, 1, 1, 0, ...enc(md5.toUpperCase())]);
  const plain = new Uint8Array(16 + core.length + pad);
  plain.set(enc("RASTATE"), 0); plain[7] = 1; plain.set(enc("MEM "), 8);
  new DataView(plain.buffer).setUint32(12, core.length + pad, true);
  plain.set(core, 16);
  for (let i = 16 + core.length, x = 7; i < plain.length; i++) { x = (x * 1103515245 + 12345) >>> 0; plain[i] = x >>> 24; } // packs poorly, as RDRAM does
  const chunk = deflateSync(plain);
  const out = new Uint8Array(24 + chunk.length);
  const v = new DataView(out.buffer);
  out.set(enc("#RZIPv\x01#"), 0);
  v.setUint32(8, 131072, true); v.setBigUint64(12, BigInt(unpacked), true); v.setUint32(20, chunk.length, true);
  out.set(chunk, 24);
  return out;
}
const STATE = rz();
const OTHER_STATE = rz({ md5: OTHER });
const gz = (n = 300) => { const b = new Uint8Array(n); b.set([0x1f, 0x8b]); return b; };

function post(fields, ip = "1.2.3.4") {
  const f = new FormData();
  for (const [k, v] of Object.entries(fields)) { if (v instanceof Uint8Array) f.append(k, new Blob([v]), k); else f.append(k, v); }
  return new Request("https://x/v1/state", { method: "POST", body: f, headers: { "cf-connecting-ip": ip } });
}
const good = (extra = {}) => ({ md5: MD5, kind: "gameplay", app: "0.2.0.1", state: STATE, ...extra });
const env = (o = {}) => ({ DB: fakeDb(), STATES: fakeR2(), WANTED, ...o });

test("a wanted game's state is stored privately with its save, and a row counts it", async () => {
  const e = env();
  const res = await worker.fetch(post(good({ save: gz() })), e);
  assert.equal(res.status, 204);
  assert.equal(e.DB.t.states.length, 1);
  const keys = [...e.STATES.files.keys()];
  assert.equal(keys.length, 2);
  assert.match(keys[0], new RegExp(`^states/${MD5}/\\d{4}-\\d\\d-\\d\\d-[0-9a-f-]{36}\\.state$`));
  assert.ok(keys[1].endsWith(".srm.gz"));
  assert.deepEqual(e.STATES.files.get(keys[0]), STATE);
  assert.equal(JSON.stringify(e.DB.t).includes("1.2.3.4"), false, "the IP address is never stored");
});

test("bad fields are refused and store nothing", async () => {
  const e = env();
  const codes = [];
  for (const f of [
    good({ md5: "zz" }), good({ kind: "other" }), good({ app: "x y" }), good({ state: "text" }),
    good({ state: new Uint8Array(4096) }), good({ state: STATE.slice(0, 100) }), good({ save: new Uint8Array(MAX_SAVE + 1) }),
    good({ kind: "problem" }), good({ save: new Uint8Array(300) }),
  ]) codes.push((await worker.fetch(post(f), e)).status);
  assert.deepEqual(codes, [400, 400, 400, 400, 400, 400, 413, 400, 400]);
  assert.equal((await worker.fetch(new Request("https://x/v1/state"), e)).status, 405);
  const json = new Request("https://x/v1/state", { method: "POST", body: "{}", headers: { "content-type": "application/json" } });
  assert.equal((await worker.fetch(json, e)).status, 400);
  assert.equal(e.DB.t.states.length, 0);
  assert.equal(e.STATES.files.size, 0);
});

test("only a real state of that game's ROM is taken: RZIP-looking junk gets 400 (audit 4 M1)", async () => {
  const e = env();
  const junk = STATE.slice(); junk.fill(0x55, 24); // the magic and header, garbage where the zlib chunk is
  const big = STATE.slice(); new DataView(big.buffer).setUint32(20, 1 << 20, true); // a first chunk past the file
  const cases = [
    ["junk after the magic", junk],
    ["another game's state", rz({ md5: OTHER })],
    ["no M64+SAVE in MEM", rz({ mem: new Uint8Array(44) })],
    ["unpacked size too small", rz({ unpacked: 1024 })],
    ["first chunk past the end", big],
  ];
  for (const [what, state] of cases) {
    const res = await worker.fetch(post(good({ state })), e);
    assert.equal(res.status, 400, what);
    assert.equal(await res.text(), "not a state of this game", what);
  }
  assert.equal(e.DB.t.states.length, 0);
  assert.equal(e.STATES.files.size, 0);
  assert.equal((await worker.fetch(post(good({ state: rz({ md5: MD5.toUpperCase() }) })), e)).status, 204, "the MD5 in either case");
});

test("a game not on the wanted list gets 404, a game with enough 410", async () => {
  const e = env();
  // The app stops asking only on a 404 whose body starts "not wanted" (any other 404, such as a route the live
  // server doesn't have yet, is retried): keep these bodies as they are (Frontend 2026-11-12).
  const nw = await worker.fetch(post(good({ md5: "f".repeat(32) })), e);
  assert.equal(nw.status, 404);
  assert.ok((await nw.text()).startsWith("not wanted"));
  for (let i = 0; i < 3; i++) assert.equal((await worker.fetch(post(good(), "10.0." + i + ".1"), e)).status, 204);
  const full = await worker.fetch(post(good(), "10.9.9.9"), e);
  assert.equal(full.status, 410);
  assert.equal(await full.text(), "have enough");
  assert.notEqual(await (await worker.fetch(new Request("https://x/v1/nothing", { method: "POST" }), env())).status, 204);
  assert.equal(DEFAULT_ENOUGH > 3, true);
});

test("the rate limit and the per-address day limit answer 429 with Retry-After", async () => {
  const limited = env({ PER_IP: { limit: async () => ({ success: false }) } });
  const r = await worker.fetch(post(good()), limited);
  assert.equal(r.status, 429);
  assert.equal(r.headers.get("retry-after"), "60");

  const e = env({ WANTED: { games: [{ md5: OTHER, kind: "problem", enough: 100 }] } });
  for (let i = 0; i < BLOCK_DAY_STATES; i++) assert.equal((await worker.fetch(post(good({ md5: OTHER, kind: "problem", state: OTHER_STATE }), "5.6.7." + i), e)).status, 204);
  const over = await worker.fetch(post(good({ md5: OTHER, kind: "problem", state: OTHER_STATE }), "5.6.7.200"), e);
  assert.equal(over.status, 429);
  assert.ok(Number(over.headers.get("retry-after")) >= 60);
  assert.equal((await worker.fetch(post(good({ md5: OTHER, kind: "problem", state: OTHER_STATE }), "8.8.8.8"), e)).status, 204, "another block still can");
});

test("an unreachable wanted list answers 503, not a store", async () => {
  const e = env({ WANTED: undefined, WANTED_URL: "http://127.0.0.1:9/none.json" });
  const r = await worker.fetch(post(good()), e);
  assert.equal(r.status, 503);
  assert.equal(e.DB.t.states.length, 0);
});

test("reports still work beside it", async () => {
  const res = await worker.fetch(new Request("https://x/health"), env());
  assert.equal(await res.text(), "ok");
});
