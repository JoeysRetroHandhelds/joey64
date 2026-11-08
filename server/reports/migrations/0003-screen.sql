-- 2026-11-08: the main screen's size and density (Joey: exact screen sizes for the adaptive layouts). Run once
-- on a database made from the schema.sql before that day (the live one): ADD COLUMN can't run twice.
-- wrangler d1 execute joey64-reports --remote --file migrations/0003-screen.sql
ALTER TABLE reports ADD COLUMN screen_w INTEGER;
ALTER TABLE reports ADD COLUMN screen_h INTEGER;
ALTER TABLE reports ADD COLUMN screen_dpi INTEGER;
