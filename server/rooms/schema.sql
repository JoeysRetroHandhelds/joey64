-- Joey64 public Netplay rooms (D1). Only what the list shows, the room's secret and when it was posted.
-- A row not posted for 75 s is no longer listed and is deleted at the next post. No IP address column:
-- the host's public address is in the code, which is the point of a public room.
-- Apply: wrangler d1 execute joey64-rooms --remote --file schema.sql (--local for wrangler dev).

CREATE TABLE IF NOT EXISTS rooms (
  id       TEXT PRIMARY KEY,  -- random, made by the app for this run
  secret   TEXT NOT NULL,     -- random, made by the app; needed to change or remove the room
  name     TEXT NOT NULL,     -- the host's name (RetroAchievements name, else the device's name)
  device   TEXT NOT NULL,     -- "AYN Thor"
  game     TEXT NOT NULL,     -- the host's file name without extension
  md5      TEXT NOT NULL,     -- the ROM's MD5: joiners need the same copy
  players  INTEGER NOT NULL,  -- 1-4 in the room now
  code     TEXT NOT NULL UNIQUE,  -- the room code ("K7Q4MD2X"): address and port
  created  INTEGER NOT NULL,  -- ms, first post
  seen     INTEGER NOT NULL,  -- ms, last post
  verified INTEGER NOT NULL DEFAULT 0,  -- 1: posted over IPv4 from the code's own address (code audit 3, M24)
  via      TEXT               -- posted over IPv6: SHA-256 of the day's salt and the poster's /64 (one room each)
);
CREATE INDEX IF NOT EXISTS rooms_seen ON rooms (seen);

-- The salt for the day's IPv6 /64 hashes, made by the day's first such post; earlier days' are deleted then.
CREATE TABLE IF NOT EXISTS salts (
  day   TEXT PRIMARY KEY,  -- yyyy-mm-dd, UTC
  salt  TEXT NOT NULL
) WITHOUT ROWID;
