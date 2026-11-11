import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";
import { MAX_UNVERIFIED, TTL_MS, isPrivate } from "../src/rules.js";
import { decode, encode } from "../src/code.js";

/** D1 over node's own SQLite, with schema.sql: the worker's SQL runs for real. */
function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  const prep = (sql) => ({
    args: [],
    bind(...a) { this.args = a; return this; },
    async first(col) { const r = db.prepare(sql).get(...this.args); return col ? r?.[col] : r ?? null; },
    async all() { return { results: db.prepare(sql).all(...this.args).map((r) => ({ ...r })) }; },
    async run() { db.prepare(sql).run(...this.args); },
  });
  return { prepare: prep, async batch(list) { for (const s of list) await s.run(); }, raw: db };
}

const hex = (c) => c.repeat(32);
const room = (over = {}) => ({
  id: hex("a"), secret: hex("b"), name: "Joey", device: "AYN Thor", game: "Mario Kart 64 (USA)",
  md5: "0123456789ABCDEF0123456789ABCDEF", players: 1, code: encode("203.0.113.7", 45000), ...over,
});
const req = (method, path, body, headers = {}) => new Request("https://x" + path, {
  method, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.7", ...headers },
});
const list = async (env) => (await (await worker.fetch(req("GET", "/rooms"), env)).json()).rooms;

test("the code matches the app's RoomCode", () => {
  // Same values as RoomCodeTest: a round trip, and every one-character typo caught.
  for (const [ip, port] of [["203.0.113.7", 45000], ["192.168.1.23", 45007], ["255.255.255.255", 45003], ["0.0.0.0", 45000]])
    assert.deepEqual(decode(encode(ip, port)), { ip, port });
  const code = encode("198.51.100.42", 45002);
  assert.equal(decode(code.slice(0, 4) + " " + code.slice(4).toLowerCase()).port, 45002);
  for (let i = 0; i < 8; i++) for (const ch of "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    if (ch !== code[i]) assert.equal(decode(code.slice(0, i) + ch + code.slice(i + 1)), null);
  assert.equal(decode("K7Q4MD2O"), null);
});

test("a posted room is listed without its secret, and refreshed by the same id", async () => {
  const env = { DB: d1() };
  assert.equal((await worker.fetch(req("POST", "/rooms", room()), env)).status, 204);
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ players: 3, name: " Joey‮\tx " })), env)).status, 204);
  const rooms = await list(env);
  assert.equal(rooms.length, 1);
  assert.deepEqual(rooms[0], { id: hex("a"), name: "Joey x", device: "AYN Thor", game: "Mario Kart 64 (USA)",
    md5: "0123456789abcdef0123456789abcdef", players: 3, code: encode("203.0.113.7", 45000) });
});

test("only the secret changes or removes a room", async () => {
  const env = { DB: d1() };
  await worker.fetch(req("POST", "/rooms", room()), env);
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ secret: hex("c"), name: "Mallory" })), env)).status, 403);
  await worker.fetch(req("DELETE", "/rooms/" + hex("a"), undefined, { "x-room-secret": hex("c") }), env);
  assert.equal((await list(env)).length, 1);
  assert.equal((await worker.fetch(req("DELETE", "/rooms/" + hex("a"), undefined, { "x-room-secret": hex("b") }), env)).status, 204);
  assert.equal((await list(env)).length, 0);
});

test("the code has to be the poster's own address", async () => {
  const env = { DB: d1() };
  const res = await worker.fetch(req("POST", "/rooms", room({ code: encode("198.51.100.9", 45000) })), env);
  assert.equal(res.status, 400);
  assert.equal(await res.text(), "code is not this address");
  // From IPv6 the address can't be checked; a private one is still refused.
  const v6 = { "cf-connecting-ip": "2001:db8::1" };
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ code: encode("198.51.100.9", 45000) }), v6), env)).status, 204);
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ id: hex("d"), code: encode("192.168.1.5", 45000) }), v6), env)).status, 400);
  assert.ok(isPrivate("100.64.0.1") && isPrivate("172.20.1.1") && !isPrivate("172.32.1.1") && !isPrivate("8.8.8.8"));
});

test("bad fields are refused", async () => {
  const env = { DB: d1() };
  for (const [bad, why] of [[{ id: "x" }, "id"], [{ md5: "zz" }, "md5"], [{ players: 5 }, "players"], [{ players: "2" }, "players"],
    [{ code: "AAAAAAAB" }, "code"], [{ name: "" }, "name"], [{ name: "x".repeat(49) }, "name too long"], [{ game: 3 }, "game"]]) {
    const res = await worker.fetch(req("POST", "/rooms", room(bad)), env);
    assert.equal(res.status, 400, JSON.stringify(bad));
    assert.equal(await res.text(), why);
  }
  assert.equal((await worker.fetch(req("POST", "/rooms", "{x"), env)).status, 400);
  assert.equal((await worker.fetch(req("POST", "/rooms", "x".repeat(3000)), env)).status, 413);
  assert.equal((await worker.fetch(req("POST", "/rooms", "{}", { "content-length": "5000" }), env)).status, 413);
  assert.equal((await worker.fetch(req("PUT", "/rooms", room()), env)).status, 405);
  assert.equal((await worker.fetch(req("GET", "/nope"), env)).status, 404);
});

test("a room not posted for 75 s leaves the list, and a new room on the same port replaces the old", async () => {
  const env = { DB: d1() };
  await worker.fetch(req("POST", "/rooms", room()), env);
  env.DB.raw.prepare("UPDATE rooms SET seen = seen - ?").run(TTL_MS + 1);
  assert.equal((await list(env)).length, 0);
  await worker.fetch(req("POST", "/rooms", room({ id: hex("e"), secret: hex("f") })), env);
  assert.equal(env.DB.raw.prepare("SELECT count(*) AS n FROM rooms").get().n, 1, "the stale row went at the next post");
  await worker.fetch(req("POST", "/rooms", room({ id: hex("1"), secret: hex("2"), name: "Joey again" })), env);
  const rooms = await list(env);
  assert.deepEqual(rooms.map((r) => r.name), ["Joey again"]);
});

test("one address gets at most four rooms", async () => {
  const env = { DB: d1() };
  const codes = [0, 1, 2, 3, 4].map((p) => encode("203.0.113.7", 45000 + p));
  const st = [];
  for (let i = 0; i < 5; i++) st.push((await worker.fetch(req("POST", "/rooms", room({ id: String(i).repeat(32), code: codes[i] })), env)).status);
  assert.deepEqual(st, [204, 204, 204, 204, 429]);
});

test("rate limited per address", async () => {
  let n = 0;
  const env = { DB: d1(), POSTS: { limit: async () => ({ success: ++n <= 1 }) }, READS: { limit: async () => ({ success: false }) } };
  assert.equal((await worker.fetch(req("POST", "/rooms", room()), env)).status, 204);
  assert.equal((await worker.fetch(req("POST", "/rooms", room()), env)).status, 429);
  assert.equal((await worker.fetch(req("GET", "/rooms"), env)).status, 429);
});

// Code audit 3, M24: a post over IPv6 can't show its code's IPv4 is its own.
const v6 = (a) => ({ "cf-connecting-ip": a });

test("an IPv6 post never takes a verified room's code, and an IPv4 post from the address takes it back", async () => {
  const env = { DB: d1() };
  assert.equal((await worker.fetch(req("POST", "/rooms", room()), env)).status, 204);
  const res = await worker.fetch(req("POST", "/rooms", room({ id: hex("e"), secret: hex("f"), name: "Mallory" }), v6("2001:db8::1")), env);
  assert.equal(res.status, 409);
  assert.deepEqual((await list(env)).map((r) => r.name), ["Joey"]);
  // Listed from IPv6 while the code is free; the address's own host (over IPv4) then replaces it.
  const other = encode("198.51.100.9", 45001);
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ id: hex("e"), secret: hex("f"), name: "Mallory", code: other }), v6("2001:db8::1")), env)).status, 204);
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ id: hex("1"), secret: hex("2"), name: "Real host", code: other }), v6("198.51.100.9")), env)).status, 204);
  assert.deepEqual((await list(env)).map((r) => r.name).sort(), ["Joey", "Real host"]);
});

test("one room per IPv6 /64, and a few in all", async () => {
  const env = { DB: d1() };
  const post = (n, from, ip = `198.51.${n}.9`) =>
    worker.fetch(req("POST", "/rooms", room({ id: n.toString(16).padStart(32, "0"), code: encode(ip, 45000) }), v6(from)), env);
  assert.equal((await post(1, "2001:db8:0:1::1")).status, 204);
  assert.equal((await post(2, "2001:db8:0:1::2")).status, 429, "the same /64");
  assert.equal((await post(2, "2001:db8:0:2::1")).status, 204, "another /64");
  for (let n = 3; n <= MAX_UNVERIFIED; n++) assert.equal((await post(n, `2001:db8:0:${n}::1`)).status, 204);
  assert.equal((await post(MAX_UNVERIFIED + 1, "2001:db8:1:1::1")).status, 503);
  // IPv4 hosts still get in.
  assert.equal((await worker.fetch(req("POST", "/rooms", room({ id: hex("c") })), env)).status, 204);
  const stored = JSON.stringify(env.DB.raw.prepare("SELECT * FROM rooms").all());
  assert.equal(stored.includes("2001:db8"), false, "no IPv6 address or /64 is stored");
});

test("a room's check goes when its code changes", async () => {
  const env = { DB: d1() };
  await worker.fetch(req("POST", "/rooms", room()), env);
  const verified = () => env.DB.raw.prepare("SELECT verified FROM rooms WHERE id = ?").get(hex("a")).verified;
  assert.equal(verified(), 1);
  await worker.fetch(req("POST", "/rooms", room({ players: 2 }), v6("2001:db8::1")), env);
  assert.equal(verified(), 1, "a refresh over IPv6 with the same code keeps it");
  await worker.fetch(req("POST", "/rooms", room({ code: encode("198.51.100.9", 45000) }), v6("2001:db8::1")), env);
  assert.equal(verified(), 0, "another code over IPv6 isn't checked");
});

test("the limiter counts IPv6 by its /64", async () => {
  const keys = [];
  const env = { DB: d1(), POSTS: { limit: async ({ key }) => { keys.push(key); return { success: true }; } } };
  await worker.fetch(req("POST", "/rooms", room({ code: encode("198.51.100.9", 45000) }), v6("2001:db8:5:6::1")), env);
  await worker.fetch(req("POST", "/rooms", room()), env);
  assert.deepEqual(keys, ["2001:db8:5:6::/64", "203.0.113.7"]);
});
