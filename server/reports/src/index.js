import { Invalid, MAX_BYTES, validate } from "./validate.js";
import { BLOCK_DAY_BYTES, BLOCK_DAY_ROWS, DAY_ROWS, PER_INSTALL_DAY, rowsOf, store, today } from "./store.js";
import { blockOf, limiterKeyOf, scramble } from "./address.js";

/**
 * Joey64's report server (Joey 2026-11-07: "a server collecting reports, so that we can get information on
 * best drivers to use, settings, box art fixed and more"). Devices whose user turned reports on POST a
 * batch of events to /v1/report; nothing is ever served back. See README.md for what a report holds and
 * what is kept out of it.
 *
 * The app is open source, so anyone can find this address and post here. So: every report is checked
 * field by field before anything is stored (validate.js), one IP address gets a few posts a minute (the
 * address is used for that count only and never stored), one install a few reports a day, one address
 * block (a /24 or /64) a share of the day's writes, counted under a hash with a key that goes with the day,
 * and the whole server a day's writes under D1's free plan (code audit 3, M23).
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return text(200, "ok");
    if (url.pathname !== "/v1/report") return text(404, "not found");
    if (request.method !== "POST") return text(405, "POST only");

    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (env.PER_IP) {
      const { success } = await env.PER_IP.limit({ key: limiterKeyOf(ip) });
      if (!success) return text(429, "slow down");
    }

    const length = Number(request.headers.get("content-length") || "0");
    if (length > MAX_BYTES) return text(413, "too big");
    const raw = await request.text();
    if (raw.length > MAX_BYTES) return text(413, "too big");

    let report;
    try {
      report = validate(JSON.parse(raw));
    } catch (e) {
      if (e instanceof SyntaxError) return text(400, "not JSON");
      if (e instanceof Invalid) return text(400, e.message);
      throw e;
    }

    const day = new Date().toISOString().slice(0, 10);
    const sent = await env.DB.prepare("SELECT count(*) AS n FROM reports WHERE install = ? AND day = ?")
      .bind(report.install, day).first("n");
    if (sent >= PER_INSTALL_DAY) return text(429, "enough for today");

    // The day's writes, then this address block's share of them: over either, the app sends it again tomorrow.
    const count = { rows: rowsOf(report), bytes: new TextEncoder().encode(raw).length };
    const t = await today(env.DB, day);
    if (t.written + count.rows > DAY_ROWS) return text(429, "full for today");
    count.who = await scramble(t.salt, blockOf(ip));
    const mine = await env.DB.prepare("SELECT written, bytes FROM posters WHERE day = ? AND who = ?").bind(day, count.who).first();
    if ((mine?.written ?? 0) + count.rows > BLOCK_DAY_ROWS || (mine?.bytes ?? 0) + count.bytes > BLOCK_DAY_BYTES) {
      return text(429, "enough from this address today");
    }

    await store(env.DB, report, day, count);
    return text(204, null);
  },
};

function text(status, body) {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
