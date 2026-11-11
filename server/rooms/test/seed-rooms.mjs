// Keeps three made-up public rooms on a local `wrangler dev` (test/local-run.sh) for screen checks: posts them
// every 30 s, as a host would, until killed. Their addresses are documentation ones, so nobody answers a ping.
//   node test/seed-rooms.mjs [base]   (default http://127.0.0.1:8798)
import { encode } from "../src/code.js";

const base = process.argv[2] || "http://127.0.0.1:8798";
const rooms = [
  { id: "1".repeat(32), name: "Kim", device: "Odin 3 Portal", game: "Super Smash Bros. (USA)", md5: "f7c52568a31aadf26e14dc2b6416b2ed", players: 3, code: encode("203.0.113.7", 45000) },
  { id: "2".repeat(32), name: "Alex", device: "Retroid Pocket 5", game: "Mario Kart 64 (USA)", md5: "3a67d9986f54eb282924fca4cd5f6dff", players: 1, code: encode("198.51.100.20", 45001) },
  { id: "3".repeat(32), name: "Robin", device: "Pixel 9", game: "Perfect Dark (USA) (Rev 1)", md5: "e03b088b6ac9e0080440efed07c1e40f", players: 2, code: encode("198.51.100.77", 45000) },
];
for (;;) {
  for (const r of rooms) {
    try {
      await fetch(base + "/rooms", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...r, secret: "f".repeat(32) }) });
    } catch (e) { console.log("post failed", e.message); }
  }
  await new Promise((r) => setTimeout(r, 30_000));
}
