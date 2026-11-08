-- 2026-11-08: the session details (failed boots, stutter, audio, input lag, heat, context). Run once on a
-- database made from the schema.sql before that day (the live one): ADD COLUMN can't run twice.
-- wrangler d1 execute joey64-reports --remote --file migrations/0002-session-details.sql
ALTER TABLE sessions ADD COLUMN frames INTEGER;
ALTER TABLE sessions ADD COLUMN first_frame_ms INTEGER;
ALTER TABLE sessions ADD COLUMN frames_timed INTEGER;
ALTER TABLE sessions ADD COLUMN p95_ms INTEGER;
ALTER TABLE sessions ADD COLUMN p99_ms INTEGER;
ALTER TABLE sessions ADD COLUMN slow_frames INTEGER;
ALTER TABLE sessions ADD COLUMN lag_ms REAL;
ALTER TABLE sessions ADD COLUMN lag_max_ms REAL;
ALTER TABLE sessions ADD COLUMN thermal_max INTEGER;
ALTER TABLE sessions ADD COLUMN headroom_max REAL;
ALTER TABLE sessions ADD COLUMN thermal_drops INTEGER;
ALTER TABLE sessions ADD COLUMN rdp_sync INTEGER;
ALTER TABLE sessions ADD COLUMN cpu_core TEXT;
ALTER TABLE sessions ADD COLUMN refresh_hz INTEGER;
ALTER TABLE sessions ADD COLUMN cheats INTEGER;
ALTER TABLE sessions ADD COLUMN from_state INTEGER;
