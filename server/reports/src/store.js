// Storing a checked report. Kept out of index.js: a Worker's main module may export only its handlers.

/** Reports one install can send in a day; the app sends one a day, plus retries. */
export const PER_INSTALL_DAY = 6;

/** One report row, then its events, in one batch (D1 runs a batch as one transaction). */
export async function store(db, r, day) {
  const row = await db.prepare(
    "INSERT INTO reports (install, day, app, core, maker, model, soc, gpu, driver, android) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
  ).bind(r.install, day, r.app, r.core, r.maker, r.model, r.soc, r.gpu, r.driver, r.android).first();
  const id = row.id;
  const statements = r.events.map((e) => {
    switch (e.type) {
      case "session":
        return db.prepare(
          "INSERT INTO sessions (report, md5, header, renderer, scale, settings, seconds, emu_fps, drawn_fps, dupes, underruns, ended, frames, first_frame_ms, frames_timed, p95_ms, p99_ms, slow_frames, lag_ms, lag_max_ms, thermal_max, headroom_max, thermal_drops, rdp_sync, cpu_core, refresh_hz, cheats, from_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).bind(id, e.md5, e.header, e.renderer, e.scale, e.settings, e.seconds, e.emu_fps, e.drawn_fps, e.dupes, e.underruns, e.ended,
            e.frames, e.first_frame_ms, e.frames_timed, e.p95_ms, e.p99_ms, e.slow_frames, e.lag_ms, e.lag_max_ms, e.thermal_max, e.headroom_max, e.thermal_drops, e.rdp_sync, e.cpu_core, e.refresh_hz, e.cheats, e.from_state);
      case "cover":
        return db.prepare("INSERT INTO covers (report, md5, kind, header, title, cover) VALUES (?, ?, ?, ?, ?, ?)")
          .bind(id, e.md5, e.kind, e.header, e.title, e.cover);
      case "crash":
        return db.prepare("INSERT INTO crashes (report, md5, where_, summary) VALUES (?, ?, ?, ?)")
          .bind(id, e.md5, e.where_, e.summary);
    }
  });
  try {
    await db.batch(statements);
  } catch (e) {
    // The events didn't go in: take the report row back out, so a retry isn't counted twice.
    await db.prepare("DELETE FROM reports WHERE id = ?").bind(id).run();
    throw e;
  }
}
