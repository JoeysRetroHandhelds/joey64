-- 2026-11-11 (code audit 3, M24): whether a room's code was checked against its poster's IPv4 address, the IPv6
-- poster's /64 hash, and the day's salt for it. Run once on the live database (made from schema.sql before this day):
-- ADD COLUMN can't run twice. Run it before deploying the Worker that writes them.
-- wrangler d1 execute joey64-rooms --remote --file migrations/0002-unverified.sql
ALTER TABLE rooms ADD COLUMN verified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rooms ADD COLUMN via TEXT;
CREATE TABLE IF NOT EXISTS salts (
  day   TEXT PRIMARY KEY,
  salt  TEXT NOT NULL
) WITHOUT ROWID;
