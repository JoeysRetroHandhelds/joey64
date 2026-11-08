-- How each GPU and driver runs each game: drawn fps, stutter, audio underruns a minute, input lag, heat
-- and crashes over the sessions reported (the stutter, lag and heat columns from 2026-11-08's app on).
SELECT r.gpu, r.driver, s.md5, s.renderer, count(*) AS sessions, count(DISTINCT r.install) AS installs,
       round(avg(s.emu_fps), 1) AS emu_fps, round(avg(s.drawn_fps), 1) AS drawn_fps,
       round(avg(s.p99_ms), 1) AS p99_ms, round(sum(s.slow_frames) * 60.0 / nullif(sum(s.seconds), 0), 2) AS slow_a_min,
       round(sum(s.underruns) * 60.0 / nullif(sum(s.seconds), 0), 2) AS underruns_a_min,
       round(avg(s.lag_ms), 1) AS lag_ms, max(s.thermal_max) AS thermal_max,
       sum(s.ended = 'crash') AS crashes
FROM sessions s JOIN reports r ON r.id = s.report
GROUP BY r.gpu, r.driver, s.md5, s.renderer HAVING sessions >= 3
ORDER BY r.gpu, s.md5, drawn_fps DESC;
