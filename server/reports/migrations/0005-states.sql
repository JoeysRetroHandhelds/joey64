-- 2026-11-12: states players send for games on the wanted list (POST /v1/state). Run once on the live database before
-- deploying the Worker that writes them (needs Joey's go, as does creating the R2 bucket joey64-states):
-- wrangler d1 execute joey64-reports --remote --file migrations/0005-states.sql
CREATE TABLE IF NOT EXISTS states (
  id INTEGER PRIMARY KEY,
  day TEXT NOT NULL,
  md5 TEXT NOT NULL,
  kind TEXT NOT NULL,
  app TEXT NOT NULL,
  state_bytes INTEGER NOT NULL,
  save_bytes INTEGER NOT NULL,
  who TEXT,
  r2_key TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS states_md5 ON states (md5);
CREATE INDEX IF NOT EXISTS states_day ON states (day, who);
