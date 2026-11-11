import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";
import { TTL_MS, isPrivate } from "../src/rules.js";
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
