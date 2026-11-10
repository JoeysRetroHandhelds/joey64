-- Why each GPU ran what it ran (from 2026-11-09's app on): sessions and games that didn't boot per GPU, Auto's
-- driver and renderer rules, the start-up trial's level (0 Auto's pick, 1 stepped down to the system driver,
-- 2 GLideN64 too) and who picked. A trial level above 0 on a GPU is Auto's pick failing there: look at its rule.
SELECT r.gpu, s.driver_rule, s.renderer_rule, s.trial_level, s.picked_by, r.driver, s.renderer,
       count(*) AS sessions, count(DISTINCT r.install) AS installs,
       sum(s.frames IS NOT NULL AND s.frames < 60) AS failed_boots, sum(s.ended = 'crash') AS crashes,
       round(avg(s.drawn_fps), 1) AS drawn_fps
FROM sessions s JOIN reports r ON r.id = s.report
WHERE s.driver_rule IS NOT NULL OR s.trial_level IS NOT NULL
GROUP BY r.gpu, s.driver_rule, s.renderer_rule, s.trial_level, s.picked_by, r.driver, s.renderer
ORDER BY r.gpu, s.trial_level DESC, sessions DESC;
