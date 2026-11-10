-- 2026-11-09: why a session ran the driver and renderer it ran (Joey: "Yes, to Frontend"): Auto's rules, the
-- start-up trial's level, and whether Auto or Settings picked. Run once on a database made from the schema.sql
-- before that day (the live one): ADD COLUMN can't run twice. Run it before deploying the Worker that writes them.
-- wrangler d1 execute joey64-reports --remote --file migrations/0004-gpu-rules.sql
ALTER TABLE sessions ADD COLUMN driver_rule TEXT;
ALTER TABLE sessions ADD COLUMN renderer_rule TEXT;
ALTER TABLE sessions ADD COLUMN trial_level INTEGER;
ALTER TABLE sessions ADD COLUMN picked_by TEXT;
