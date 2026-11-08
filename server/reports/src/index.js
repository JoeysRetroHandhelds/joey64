import { Invalid, MAX_BYTES, validate } from "./validate.js";
import { PER_INSTALL_DAY, store } from "./store.js";

/**
 * Joey64's report server (Joey 2026-11-07: "a server collecting reports, so that we can get information on
 * best drivers to use, settings, box art fixed and more"). Devices whose user turned reports on POST a
 * batch of events to /v1/report; nothing is ever served back. See README.md for what a report holds and
 * what is kept out of it.
 *
 * The app is open source, so anyone can find this address and post here. So: every report is checked
 * field by field before anything is stored (validate.js), one IP address gets a few posts a minute (the
 * address is used for that count only and never stored), and one install a few reports a day.
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return text(200, "ok");
    if (url.pathname !== "/v1/report") return text(404, "not found");
    if (request.method !== "POST") return text(405, "POST only");

    if (env.PER_IP) {
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      const { success } = await env.PER_IP.limit({ key: ip });
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

    await store(env.DB, report, day);
    return text(204, null);
  },
};

function text(status, body) {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
