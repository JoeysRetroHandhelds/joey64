import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { PER_INSTALL_DAY } from "../src/store.js";
import { good } from "./fixture.js";

/** Enough of D1 for the worker: rows kept in arrays by table, inserts and the one count it asks. */
function fakeDb({ failBatch = false } = {}) {
  const t = { reports: [], sessions: [], covers: [], crashes: [] };
  const prep = (sql) => ({
    args: [],
    bind(...a) { this.args = a; return this; },
    async first(col) {
      if (sql.startsWith("SELECT count(*)")) {
        const n = t.reports.filter((r) => r.install === this.args[0] && r.day === this.args[1]).length;
        return col ? n : { n };
      }
      if (sql.startsWith("INSERT INTO reports")) {
        const id = t.reports.length + 1;
        t.reports.push({ id, install: this.args[0], day: this.args[1] });
        return { id };
      }
      throw new Error("unexpected " + sql);
    },
    async run() {
      if (sql.startsWith("DELETE FROM reports")) t.reports = t.reports.filter((r) => r.id !== this.args[0]);
      else if (sql.startsWith("INSERT INTO ")) t[sql.split(" ")[2]].push(this.args);
    },
  });
  return {
    t,
    prepare: prep,
    async batch(list) {
      if (failBatch) throw new Error("D1 down");
      for (const s of list) await s.run();
    },
  };
}

const post = (body, headers = {}) => new Request("https://x/v1/report", {
  method: "POST", body: typeof body === "string" ? body : JSON.stringify(body),
  headers: { "content-type": "application/json", "cf-connecting-ip": "1.2.3.4", ...headers },
});

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
