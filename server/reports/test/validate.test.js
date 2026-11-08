import test from "node:test";
import assert from "node:assert/strict";
import { Invalid, MAX_EVENTS, validate } from "../src/validate.js";

import { good } from "./fixture.js";

const INSTALL = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b";

test("a good report keeps every event, trimmed to the tables", () => {
  const r = validate(good());
  assert.equal(r.install, INSTALL);
  assert.equal(r.gpu, "Adreno 750");
  assert.deepEqual(r.events.map((e) => e.type), ["session", "cover", "cover", "crash"]);
  assert.equal(r.events[0].settings, '{"frameskip":"auto","widescreen":true}');
  assert.equal(r.events[1].kind, "none");
  assert.equal(r.events[2].kind, "replaced");
  assert.equal(r.events[2].cover, "screenscraper:108717:us");
  assert.equal(r.events[3].where_, "core");
});

test("fields a report has no business carrying are dropped", () => {
  const b = good();
  b.ip = "1.2.3.4";
  b.device.serial = "ABC";
  b.events[1].path = "/storage/emulated/0/ROMs/x.z64";
  const r = validate(b);
  assert.equal(JSON.stringify(r).includes("1.2.3.4"), false);
  assert.equal(JSON.stringify(r).includes("ABC"), false);
  assert.equal(JSON.stringify(r).includes("/storage"), false);
});

for (const [name, change] of [
  ["unknown format", (b) => { b.format = 2; }],
  ["install not a random id", (b) => { b.install = "joey"; }],
  ["no events", (b) => { b.events = []; }],
  ["too many events", (b) => { b.events = Array(MAX_EVENTS + 1).fill(b.events[1]); }],
  ["md5 not an MD5", (b) => { b.events[0].md5 = "x"; }],
  ["header not a game code", (b) => { b.events[1].header = "N$ME"; }],
  ["unknown event type", (b) => { b.events[0].type = "spam"; }],
  ["ended", (b) => { b.events[0].ended = "maybe"; }],
  ["emuFps out of range", (b) => { b.events[0].emuFps = 1e9; }],
  ["scale not a whole number", (b) => { b.events[0].scale = 1.5; }],
  ["control characters", (b) => { b.events[1].title = "a\u0000b"; }],
  ["title too long", (b) => { b.events[1].title = "x".repeat(201); }],
  ["setting not a plain value", (b) => { b.events[0].settings = { x: { y: 1 } }; }],
  ["summary too long", (b) => { b.events[3].summary = "x".repeat(2001); }],
  ["where", (b) => { b.events[3].where = "kernel"; }],
  ["device not an object", (b) => { b.device = "Thor"; }],
]) {
  test(`rejected: ${name}`, () => {
    const b = good();
    change(b);
    assert.throws(() => validate(b), Invalid);
  });
}
