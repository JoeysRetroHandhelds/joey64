import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { BLOCK_DAY_BYTES, BLOCK_DAY_ROWS, DAY_ROWS, PER_INSTALL_DAY, rowsOf, today } from "../src/store.js";
import { blockOf, limiterKeyOf } from "../src/address.js";
import { validate } from "../src/validate.js";
import { good } from "./fixture.js";

/** Enough of D1 for the worker: rows kept in arrays by table, inserts, the counts it asks and the day's counts. */
function fakeDb({ failBatch = false } = {}) {
  const t = { reports: [], sessions: [], covers: [], crashes: [], days: {}, posters: {} };
  const prep = (sql) => ({
    sql,
    args: [],
    bind(...a) { this.args = a; return this; },
    async first(col) {
      const a = this.args;
      if (sql.startsWith("SELECT count(*)")) {
        const n = t.reports.filter((r) => r.install === a[0] && r.day === a[1]).length;
        return col ? n : { n };
      }
      if (sql.startsWith("INSERT INTO reports")) {
        const id = t.reports.length + 1;
        t.reports.push({ id, install: a[0], day: a[1] });
        return { id };
      }
      if (sql.startsWith("SELECT written, bytes, salt FROM days")) return t.days[a[0]] ? { ...t.days[a[0]] } : null;
      if (sql.startsWith("SELECT written, bytes FROM posters")) return t.posters[a[0] + " " + a[1]] ?? null;
      throw new Error("unexpected " + sql);
    },
    async run() {
      const a = this.args;
      if (sql.startsWith("DELETE FROM reports")) t.reports = t.reports.filter((r) => r.id !== a[0]);
      else if (sql.startsWith("INSERT INTO days")) { if (!t.days[a[0]]) t.days[a[0]] = { written: 0, bytes: 0, salt: a[1] }; }
      else if (sql.startsWith("DELETE FROM posters")) { for (const k of Object.keys(t.posters)) if (k.split(" ")[0] < a[0]) delete t.posters[k]; }
      else if (sql.startsWith("UPDATE days SET salt")) { for (const [d, v] of Object.entries(t.days)) if (d < a[0]) v.salt = null; }
      else if (sql.startsWith("UPDATE days SET written")) { t.days[a[2]].written += a[0]; t.days[a[2]].bytes += a[1]; }
      else if (sql.startsWith("INSERT INTO posters")) {
        const v = (t.posters[a[0] + " " + a[1]] ??= { written: 0, bytes: 0 });
        v.written += a[2]; v.bytes += a[3];
      } else if (sql.startsWith("INSERT INTO ")) t[sql.split(" ")[2]].push(a);
    },
  });
  return {
    t,
    prepare: prep,
    async batch(list) {
      // failBatch: the report's own batch fails (the one with its events), as D1 going down mid-report would.
      if (failBatch && list.some((s) => /^INSERT INTO (sessions|covers|crashes)/.test(s.sql))) throw new Error("D1 down");
      for (const s of list) await s.run();
    },
  };
}

const post = (body, headers = {}) => new Request("https://x/v1/report", {
  method: "POST", body: typeof body === "string" ? body : JSON.stringify(body),
  headers: { "content-type": "application/json", "cf-connecting-ip": "1.2.3.4", ...headers },
});

const MD5 = "9feee5da895fb2e115bb15d1c4a9f906";
/** A report from its own install, so the per-install count doesn't stop it. */
const fromInstall = (events) => ({ ...good(events), install: crypto.randomUUID() });

test("a good report is stored with its events, and nothing is sent back", async () => {
  const db = fakeDb();
  const res = await worker.fetch(post(good()), { DB: db });
  assert.equal(res.status, 204);
  assert.equal(db.t.reports.length, 1);
  assert.equal(db.t.sessions.length, 1);
  assert.equal(db.t.covers.length, 2);
  assert.equal(db.t.crashes.length, 1);
  assert.equal(JSON.stringify(db.t).includes("1.2.3.4"), false, "the IP address is never stored");
});

test("bad posts store nothing", async () => {
  const db = fakeDb();
  assert.equal((await worker.fetch(post("{nope"), { DB: db })).status, 400);
  assert.equal((await worker.fetch(post({ ...good(), format: 9 }), { DB: db })).status, 400);
  assert.equal((await worker.fetch(post("x".repeat(70_000)), { DB: db })).status, 413);
  assert.equal((await worker.fetch(new Request("https://x/v1/report"), { DB: db })).status, 405);
  assert.equal((await worker.fetch(new Request("https://x/other"), { DB: db })).status, 404);
  assert.equal(db.t.reports.length, 0);
});

test("one install gets a few reports a day", async () => {
  const db = fakeDb();
  for (let i = 0; i < PER_INSTALL_DAY; i++) assert.equal((await worker.fetch(post(good()), { DB: db })).status, 204);
  assert.equal((await worker.fetch(post(good()), { DB: db })).status, 429);
  assert.equal(db.t.reports.length, PER_INSTALL_DAY);
});

test("the IP limiter's no is a 429, before anything is read", async () => {
  const db = fakeDb();
  const env = { DB: db, PER_IP: { limit: async () => ({ success: false }) } };
  assert.equal((await worker.fetch(post(good()), env)).status, 429);
  assert.equal(db.t.reports.length, 0);
});

test("events that fail to store take their report row back out", async () => {
  const db = fakeDb({ failBatch: true });
  await assert.rejects(worker.fetch(post(good()), { DB: db }));
  assert.equal(db.t.reports.length, 0);
});

test("health answers without the database", async () => {
  assert.equal(await (await worker.fetch(new Request("https://x/health"), {})).text(), "ok");
});

// Code audit 3, M23: posts from one address could use up D1's daily writes, and every real report then failed.
test("one address block gets a share of the day, other addresses still get in", async () => {
  const db = fakeDb();
  const covers = Array.from({ length: 200 }, () => ({ type: "nocover", md5: MD5, header: "NSME", title: "Super Mario 64" }));
  const each = rowsOf(validate(good(covers)));
  const fit = Math.floor(BLOCK_DAY_ROWS / each);
  for (let i = 0; i < fit; i++) {
    // Any address in the /24 counts as the same block.
    assert.equal((await worker.fetch(post(fromInstall(covers), { "cf-connecting-ip": `1.2.3.${i + 1}` }), { DB: db })).status, 204);
  }
  const over = await worker.fetch(post(fromInstall(covers), { "cf-connecting-ip": "1.2.3.200" }), { DB: db });
  assert.equal(over.status, 429);
  assert.equal(await over.text(), "enough from this address today");
  assert.equal((await worker.fetch(post(fromInstall(covers), { "cf-connecting-ip": "1.2.4.1" }), { DB: db })).status, 204);
  assert.equal(db.t.reports.length, fit + 1);
  assert.equal(JSON.stringify(db.t).includes("1.2.3"), false, "no address, block or part of one is stored");
});

test("an address block's share has a size too", async () => {
  const db = fakeDb();
  const crashes = Array.from({ length: 30 }, () => ({ type: "crash", md5: MD5, where: "core", summary: "x".repeat(2000) }));
  const size = JSON.stringify(fromInstall(crashes)).length;
  const fit = Math.floor(BLOCK_DAY_BYTES / size);
  assert.ok(fit * rowsOf(validate(good(crashes))) < BLOCK_DAY_ROWS, "the bytes run out first here");
  for (let i = 0; i < fit; i++) assert.equal((await worker.fetch(post(fromInstall(crashes)), { DB: db })).status, 204);
  assert.equal((await worker.fetch(post(fromInstall(crashes)), { DB: db })).status, 429);
});

test("the whole day's writes stop under D1's free plan", async () => {
  const db = fakeDb();
  const day = new Date().toISOString().slice(0, 10);
  db.t.days[day] = { written: DAY_ROWS - 5, bytes: 0, salt: "s" };
  const res = await worker.fetch(post(fromInstall()), { DB: db });
  assert.equal(res.status, 429);
  assert.equal(await res.text(), "full for today");
  assert.equal(db.t.reports.length, 0);
});

test("a stored report counts its rows for the day and for its address block", async () => {
  const db = fakeDb();
  assert.equal((await worker.fetch(post(good()), { DB: db })).status, 204);
  const day = new Date().toISOString().slice(0, 10);
  const rows = rowsOf(validate(good()));
  assert.equal(db.t.days[day].written, rows);
  const [who] = Object.values(db.t.posters);
  assert.equal(who.written, rows);
  assert.equal(who.bytes, JSON.stringify(good()).length);
});

test("the next day's first post deletes the day before's address counts and salt", async () => {
  const db = fakeDb();
  const first = await today(db, "2026-11-10");
  db.t.posters["2026-11-10 abc"] = { written: 10, bytes: 100 };
  assert.equal((await today(db, "2026-11-10")).salt, first.salt, "the same salt all day");
  const next = await today(db, "2026-11-11");
  assert.notEqual(next.salt, first.salt);
  assert.equal(db.t.days["2026-11-10"].salt, null);
  assert.deepEqual(Object.keys(db.t.posters), []);
});

test("IPv6 counts by the /64, IPv4 blocks by the /24", () => {
  assert.equal(blockOf("1.2.3.4"), "1.2.3.0/24");
  assert.equal(blockOf("::ffff:1.2.3.4"), "1.2.3.0/24");
  assert.equal(limiterKeyOf("1.2.3.4"), "1.2.3.4");
  assert.equal(limiterKeyOf("2001:db8:1:2:3:4:5:6"), "2001:db8:1:2::/64");
  assert.equal(limiterKeyOf("2001:0db8:0001:0002::9"), "2001:db8:1:2::/64");
  assert.equal(blockOf("2001:db8:1:2::9"), blockOf("2001:db8:1:2:ffff:ffff:ffff:ffff"));
  assert.notEqual(blockOf("2001:db8:1:2::9"), blockOf("2001:db8:1:3::9"));
  assert.equal(blockOf("::1"), "0:0:0:0::/64");
  assert.equal(limiterKeyOf("not an address"), "not an address");
});

test("the IP limiter counts an IPv6 poster by its /64", async () => {
  const keys = [];
  const env = { DB: fakeDb(), PER_IP: { limit: async ({ key }) => { keys.push(key); return { success: true }; } } };
  await worker.fetch(post(fromInstall(), { "cf-connecting-ip": "2001:db8:1:2::1" }), env);
  await worker.fetch(post(fromInstall(), { "cf-connecting-ip": "2001:db8:1:2::2" }), env);
  assert.deepEqual(keys, ["2001:db8:1:2::/64", "2001:db8:1:2::/64"]);
});
