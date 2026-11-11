import { Invalid, MAX_PER_ADDRESS, MAX_ROOMS, TTL_MS, validate } from "./rules.js";

/**
 * Joey64's public Netplay rooms (Joey 2026-11-10: "public vs private rooms, with a lobby browser to see open
 * public rooms", kept on our Cloudflare Worker). Only the list lives here: the games connect device to device with
 * the room code, which is the host's address. A host whose room is Public (and whose router opened the port)
 * posts it every 30 s; a room not posted for 75 s is gone. Private rooms never come here.
 *
 *   GET    /rooms            {"rooms":[{id, name, device, game, md5, players, code}]}, newest first
 *   POST   /rooms            {id, secret, name, device, game, md5, players, code}: list or refresh a room
 *   DELETE /rooms/<id>       header X-Room-Secret: take it off
 *   GET    /health           ok
 *
 * The app is open source, so anyone can post. So: every field is checked; the address in the code has to be the
 * one the post comes from when that is IPv4 (nobody can list someone else's address); a room is changed or
 * removed only with the secret it was first posted with; posts and reads are rate limited per IP address, which
 * is never stored. What is stored is what the list shows, plus the secret and the time of the last post.
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const ip = request.headers.get("cf-connecting-ip") || "";
    if (url.pathname === "/health") return text(200, "ok");

    if (url.pathname === "/rooms" && request.method === "GET") {
      if (!(await allowed(env.READS, ip))) return text(429, "slow down");
      const now = Date.now();
      const { results } = await env.DB.prepare(
        "SELECT id, name, device, game, md5, players, code FROM rooms WHERE seen > ? ORDER BY created DESC LIMIT ?")
        .bind(now - TTL_MS, MAX_ROOMS).all();
      return json({ rooms: results });
    }

    if (url.pathname === "/rooms" && request.method === "POST") {
      if (!(await allowed(env.POSTS, ip))) return text(429, "slow down");
      const raw = await request.text();
      if (raw.length > 2048) return text(413, "too big");
      let room;
      try {
        room = validate(JSON.parse(raw), env.LOCAL_TEST === "1" ? "" : ip);
      } catch (e) {
        if (e instanceof SyntaxError) return text(400, "not JSON");
        if (e instanceof Invalid) return text(400, e.message);
        throw e;
      }
      const now = Date.now();
      await env.DB.prepare("DELETE FROM rooms WHERE seen <= ?").bind(now - TTL_MS).run();
      const had = await env.DB.prepare("SELECT secret FROM rooms WHERE id = ?").bind(room.id).first();
      if (had && had.secret !== room.secret) return text(403, "not your room");
      if (!had) {
        // The first six characters are the address's top 30 bits: at most a few rooms from one small block.
        const same = await env.DB.prepare("SELECT count(*) AS n FROM rooms WHERE substr(code, 1, 6) = ? AND id != ?")
          .bind(room.code.slice(0, 6), room.id).first("n");
        if (same >= MAX_PER_ADDRESS) return text(429, "enough rooms from this address");
        const all = await env.DB.prepare("SELECT count(*) AS n FROM rooms").first("n");
        if (all >= MAX_ROOMS) return text(503, "list full");
      }
      // One room per code: a new room on the same address and port replaces the old one (the host restarted).
      await env.DB.batch([
        env.DB.prepare("DELETE FROM rooms WHERE code = ? AND id != ?").bind(room.code, room.id),
        env.DB.prepare(
          `INSERT INTO rooms (id, secret, name, device, game, md5, players, code, created, seen)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET name = excluded.name, device = excluded.device, game = excluded.game,
             md5 = excluded.md5, players = excluded.players, code = excluded.code, seen = excluded.seen`)
          .bind(room.id, room.secret, room.name, room.device, room.game, room.md5, room.players, room.code, now, now),
      ]);
      return text(204, null);
    }

    const del = url.pathname.match(/^\/rooms\/([0-9a-f]{32})$/);
    if (del && request.method === "DELETE") {
      if (!(await allowed(env.POSTS, ip))) return text(429, "slow down");
      const secret = request.headers.get("x-room-secret") || "";
      await env.DB.prepare("DELETE FROM rooms WHERE id = ? AND secret = ?").bind(del[1], secret).run();
      return text(204, null);
    }

    if (url.pathname === "/rooms" || del) return text(405, "not allowed");
    return text(404, "not found");
  },
};

async function allowed(limiter, ip) {
  if (!limiter) return true;
  const { success } = await limiter.limit({ key: ip || "unknown" });
  return success;
}

function text(status, body) {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}

function json(o) {
  return new Response(JSON.stringify(o), {
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
