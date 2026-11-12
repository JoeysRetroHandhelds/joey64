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
  android   INTEGER,        -- SDK level
  screen_w  INTEGER,        -- the main screen in pixels, long side (migrations/0003-screen.sql)
  screen_h  INTEGER,        -- short side
  screen_dpi INTEGER        -- its density; dp = px * 160 / dpi
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
  from_state      INTEGER,   -- 1: started from a save state (Continue)
  driver_rule     TEXT,      -- Auto's driver rule (GpuRules): "Adreno 6xx-8xx: bundled Turnip" (migrations/0004-gpu-rules.sql)
  renderer_rule   TEXT,      -- Auto's renderer rule, or the per-game one when that won
  trial_level     INTEGER,   -- GpuTrial: 0 Auto's pick, 1 system driver, 2 GLideN64 too; NULL: no trial (Settings picked)
  picked_by       TEXT       -- "auto" or "user" (driver or renderer set in Settings; the rules then say what Auto would pick)
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

-- The day's totals (code audit 3, M23): rows written, kept under D1's free plan (store.js DAY_ROWS), bytes posted, and
-- the random salt the day's address counts are hashed with. The day's first post makes the row; the next day's first
-- post sets its salt to NULL, so the day's hashes can't be matched to an address after it.
CREATE TABLE IF NOT EXISTS days (
  day      TEXT PRIMARY KEY,  -- yyyy-mm-dd, UTC
  written  INTEGER NOT NULL,  -- rows written by the day's reports, as D1 counts them (store.js rowsOf)
  bytes    INTEGER NOT NULL,  -- the reports' size as posted
  salt     TEXT               -- NULL once the day is over
) WITHOUT ROWID;

-- One address block's share of the day (an IPv4 /24 or an IPv6 /64), under its hash with the day's salt, never the
-- address. Rows of earlier days are deleted by the next day's first post.
CREATE TABLE IF NOT EXISTS posters (
  day      TEXT NOT NULL,
  who      TEXT NOT NULL,     -- SHA-256 of the day's salt and the block, first 16 bytes in hex
  written  INTEGER NOT NULL,
  bytes    INTEGER NOT NULL,
  PRIMARY KEY (day, who)
) WITHOUT ROWID;

-- States players sent for games on the wanted list (POST /v1/state, src/state.js; 2026-11-12). The files are in the
-- R2 bucket joey64-states under r2_key (.state, and .srm.gz when a save came with it); who is the address block's hash,
-- for that day's count only (NULL after the day).
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
