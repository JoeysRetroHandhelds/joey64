-- Joey64 reports (D1). One row per report a device sends, and its events in their own tables.
-- Kept on purpose: no IP address, no time finer than the day, no account names, no folder paths.
-- Apply: wrangler d1 execute joey64-reports --remote --file schema.sql (--local for wrangler dev).

CREATE TABLE IF NOT EXISTS reports (
  id        INTEGER PRIMARY KEY,
  install   TEXT NOT NULL,  -- the app's random install id (the user can reset it)
  day       TEXT NOT NULL,  -- yyyy-mm-dd, UTC, when it arrived
  app       TEXT NOT NULL,  -- versionName
  core      TEXT,           -- core commit or md5
  maker     TEXT,
  model     TEXT,
  soc       TEXT,
  gpu       TEXT,           -- "Adreno 750"
  driver    TEXT,           -- the Vulkan driver in use: "system 0512.744" or "Turnip 25.1"
  android   INTEGER         -- SDK level
);
CREATE INDEX IF NOT EXISTS reports_install_day ON reports (install, day);

-- One play session: what ran and how it went.
CREATE TABLE IF NOT EXISTS sessions (
  report     INTEGER NOT NULL REFERENCES reports (id),
  md5        TEXT NOT NULL,   -- the ROM's MD5 (the library's game key)
  header     TEXT,            -- the ROM header's game code, "NSME"
  renderer   TEXT,            -- "parallel" or "gliden64"
  scale      INTEGER,         -- internal resolution, 1 = native
  settings   TEXT,            -- JSON: the settings that differ from the defaults
  seconds    INTEGER,         -- played
  emu_fps    REAL,            -- averages over the session
  drawn_fps  REAL,
  dupes      REAL,            -- duplicate frames a second
  underruns  REAL,            -- audio underruns a minute
  ended      TEXT NOT NULL    -- "quit" or "crash"
);
CREATE INDEX IF NOT EXISTS sessions_md5 ON sessions (md5);

-- Box art: a library game that got no cover, or one whose downloaded cover the user replaced (a wrong cover, most likely).
CREATE TABLE IF NOT EXISTS covers (
  report  INTEGER NOT NULL REFERENCES reports (id),
  md5     TEXT NOT NULL,
  kind    TEXT NOT NULL,   -- "none" or "replaced"
  header  TEXT,
  title   TEXT,            -- the file name without extension, or the library's title
  cover   TEXT             -- for "replaced": the cover it had, "screenscraper:<game>:<region>"
);
CREATE INDEX IF NOT EXISTS covers_md5 ON covers (md5);

-- A crash the app noticed (a session that didn't end, or a caught fatal error).
CREATE TABLE IF NOT EXISTS crashes (
  report   INTEGER NOT NULL REFERENCES reports (id),
  md5      TEXT,
  where_   TEXT NOT NULL,  -- "core", "launcher", "host"
  summary  TEXT NOT NULL   -- the first lines of the error, at most 2,000 characters
);
