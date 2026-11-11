// Storing a checked report. Kept out of index.js: a Worker's main module may export only its handlers.
import { newSalt } from "./address.js";

/** Reports one install can send in a day; the app sends one a day, plus retries. */
export const PER_INSTALL_DAY = 6;

/**
 * Rows the whole server writes in a day, under D1's free 100,000 (code audit 3, M23: one address could post 250 full
 * reports in 25 minutes, and every real report then failed until 00:00 UTC). The rest is room for queries.
 */
export const DAY_ROWS = 80_000;
/** One /24 or /64's share of a day: about 270 everyday reports, or 8 first reports with 150 covers each. */
export const BLOCK_DAY_ROWS = 3_000;
/** And of the bytes posted, so one address can't fill the 500 MB database either: 16 full reports. */
export const BLOCK_DAY_BYTES = 1 << 20;

/**
 * Rows a report writes as D1 counts them (README "Free plan limits"): the report row and its index entry, a session or
 * cover and its md5 index entry, a crash, and the day's two counts.
 */
export function rowsOf(r) {
  let n = 2 + 2;
  for (const e of r.events) n += e.type === "crash" ? 1 : 2;
  return n;
}

/**
 * The day's row: rows written, bytes posted, and the salt its address counts are hashed with. The day's first post
 * makes it with a new random salt and deletes the days before's address counts and salts, so none outlives its day.
 */
export async function today(db, day) {
  const read = () => db.prepare("SELECT written, bytes, salt FROM days WHERE day = ?").bind(day).first();
  const row = await read();
  if (row?.salt) return row;
  await db.batch([
    db.prepare("INSERT INTO days (day, written, bytes, salt) VALUES (?, 0, 0, ?) ON CONFLICT (day) DO NOTHING").bind(day, newSalt()),
    db.prepare("DELETE FROM posters WHERE day < ?").bind(day),
    db.prepare("UPDATE days SET salt = NULL WHERE day < ? AND salt IS NOT NULL").bind(day),
  ]);
  return await read();
}

/**
 * One report row, then its events, in one batch (D1 runs a batch as one transaction), with [count] (rows, bytes, and
 * `who`, the address block's hash) added to the day's and the block's counts in the same batch.
 */
export async function store(db, r, day, count) {
  const row = await db.prepare(
    "INSERT INTO reports (install, day, app, core, maker, model, soc, gpu, driver, android, screen_w, screen_h, screen_dpi) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
  ).bind(r.install, day, r.app, r.core, r.maker, r.model, r.soc, r.gpu, r.driver, r.android, r.screen_w, r.screen_h, r.screen_dpi).first();
  const id = row.id;
  const statements = r.events.map((e) => {
    switch (e.type) {
      case "session":
        return db.prepare(
          "INSERT INTO sessions (report, md5, header, renderer, scale, settings, seconds, emu_fps, drawn_fps, dupes, underruns, ended, frames, first_frame_ms, frames_timed, p95_ms, p99_ms, slow_frames, lag_ms, lag_max_ms, thermal_max, headroom_max, thermal_drops, rdp_sync, cpu_core, refresh_hz, cheats, from_state, driver_rule, renderer_rule, trial_level, picked_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).bind(id, e.md5, e.header, e.renderer, e.scale, e.settings, e.seconds, e.emu_fps, e.drawn_fps, e.dupes, e.underruns, e.ended,
            e.frames, e.first_frame_ms, e.frames_timed, e.p95_ms, e.p99_ms, e.slow_frames, e.lag_ms, e.lag_max_ms, e.thermal_max, e.headroom_max, e.thermal_drops, e.rdp_sync, e.cpu_core, e.refresh_hz, e.cheats, e.from_state,
            e.driver_rule, e.renderer_rule, e.trial_level, e.picked_by);
      case "cover":
        return db.prepare("INSERT INTO covers (report, md5, kind, header, title, cover) VALUES (?, ?, ?, ?, ?, ?)")
          .bind(id, e.md5, e.kind, e.header, e.title, e.cover);
      case "crash":
        return db.prepare("INSERT INTO crashes (report, md5, where_, summary) VALUES (?, ?, ?, ?)")
          .bind(id, e.md5, e.where_, e.summary);
    }
  });
  if (count) {
    statements.push(
      db.prepare("UPDATE days SET written = written + ?, bytes = bytes + ? WHERE day = ?").bind(count.rows, count.bytes, day),
      db.prepare("INSERT INTO posters (day, who, written, bytes) VALUES (?, ?, ?, ?) ON CONFLICT (day, who) DO UPDATE SET written = written + excluded.written, bytes = bytes + excluded.bytes")
        .bind(day, count.who, count.rows, count.bytes),
    );
  }
  try {
    await db.batch(statements);
  } catch (e) {
    // The events didn't go in: take the report row back out, so a retry isn't counted twice.
    await db.prepare("DELETE FROM reports WHERE id = ?").bind(id).run();
    throw e;
  }
}
