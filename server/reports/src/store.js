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
          "INSERT INTO sessions (report, md5, header, renderer, scale, settings, seconds, emu_fps, drawn_fps, dupes, underruns, ended) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).bind(id, e.md5, e.header, e.renderer, e.scale, e.settings, e.seconds, e.emu_fps, e.drawn_fps, e.dupes, e.underruns, e.ended);
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
