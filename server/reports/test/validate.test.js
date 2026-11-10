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
  assert.equal(r.events[0].p99_ms, 14);
  assert.equal(r.events[0].rdp_sync, 0);
  assert.equal(r.events[0].from_state, 1);
  assert.equal(r.events[0].cpu_core, "dynarec");
  assert.deepEqual([r.screen_w, r.screen_h, r.screen_dpi], [1920, 1080, 369]);
  assert.equal(r.events[0].driver_rule, "Adreno 6xx-8xx: bundled Turnip");
  assert.equal(r.events[0].renderer_rule, "custom driver: parallel-RDP");
  assert.equal(r.events[0].trial_level, 0);
  assert.equal(r.events[0].picked_by, "auto");
});

test("an older app's device, without the screen, is taken", () => {
  const b = good();
  for (const k of ["screenW", "screenH", "dpi"]) delete b.device[k];
  const r = validate(b);
  assert.deepEqual([r.screen_w, r.screen_h, r.screen_dpi], [null, null, null]);
});

test("an older app's session, without the details, is taken", () => {
  const b = good();
  for (const k of ["frames", "firstFrameMs", "framesTimed", "p95Ms", "p99Ms", "slowFrames", "lagMs", "lagMaxMs", "thermalMax",
    "headroomMax", "thermalDrops", "rdpSync", "cpuCore", "refreshHz", "cheats", "fromState", "driverRule", "rendererRule", "trialLevel",
    "pickedBy"]) delete b.events[0][k];
  const r = validate(b);
  assert.equal(r.events[0].frames, null);
  assert.equal(r.events[0].cpu_core, null);
  assert.equal(r.events[0].driver_rule, null);
  assert.equal(r.events[0].trial_level, null);
  assert.equal(r.events[0].picked_by, null);
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
  ["p99Ms out of range", (b) => { b.events[0].p99Ms = 101; }],
  ["thermalMax out of range", (b) => { b.events[0].thermalMax = 7; }],
  ["rdpSync not true or false", (b) => { b.events[0].rdpSync = 1; }],
  ["cpuCore", (b) => { b.events[0].cpuCore = "jit"; }],
  ["frames not a whole number", (b) => { b.events[0].frames = 1.5; }],
  ["device not an object", (b) => { b.device = "Thor"; }],
  ["screenW out of range", (b) => { b.device.screenW = 99999; }],
  ["dpi not a whole number", (b) => { b.device.dpi = 369.5; }],
  ["driverRule too long", (b) => { b.events[0].driverRule = "x".repeat(121); }],
  ["rendererRule not a string", (b) => { b.events[0].rendererRule = 4; }],
  ["trialLevel out of range", (b) => { b.events[0].trialLevel = 3; }],
  ["trialLevel not a whole number", (b) => { b.events[0].trialLevel = 0.5; }],
  ["pickedBy", (b) => { b.events[0].pickedBy = "dev"; }],
]) {
  test(`rejected: ${name}`, () => {
    const b = good();
    change(b);
    assert.throws(() => validate(b), Invalid);
  });
}
