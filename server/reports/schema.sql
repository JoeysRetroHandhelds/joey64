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
  underruns  INTEGER,         -- audio underruns (the buffer ran dry: a crackle) in the session
  ended      TEXT NOT NULL,   -- "quit" or "crash"
  frames          INTEGER,   -- frames drawn; few after 5 s or more is a game that didn't boot
  first_frame_ms  INTEGER,   -- from loading the game to its first frame
  frames_timed    INTEGER,   -- frames in p95_ms / p99_ms
  p95_ms          INTEGER,   -- frame work time (emulation and present, no waits), 95th and 99th percentile, whole ms
  p99_ms          INTEGER,
  slow_frames     INTEGER,   -- frames whose work took over 33 ms (a skipped frame even at normal speed)
  lag_ms          REAL,      -- input poll to display, mean and max (parallel-RDP only)
  lag_max_ms      REAL,
  thermal_max     INTEGER,   -- highest Android thermal status: 0 none .. 6 shutdown
  headroom_max    REAL,      -- highest thermal headroom (1.0 = severe throttling starts)
  thermal_drops   INTEGER,   -- times Thermal fallback lowered the fast-forward speed
  rdp_sync        INTEGER,   -- parallel-RDP synchronous 1 / async 0
  cpu_core        TEXT,      -- "dynarec" or "interpreter"
  refresh_hz      INTEGER,   -- the screen's refresh rate
  cheats          INTEGER,   -- most cheat codes on at once
  from_state      INTEGER    -- 1: started from a save state (Continue)
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
