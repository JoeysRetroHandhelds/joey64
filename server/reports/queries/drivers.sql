-- How each GPU and driver runs each game: drawn fps, underruns and crashes over the sessions reported.
SELECT r.gpu, r.driver, s.md5, s.renderer, count(*) AS sessions, count(DISTINCT r.install) AS installs,
       round(avg(s.emu_fps), 1) AS emu_fps, round(avg(s.drawn_fps), 1) AS drawn_fps, round(avg(s.underruns), 1) AS underruns,
       sum(s.ended = 'crash') AS crashes
FROM sessions s JOIN reports r ON r.id = s.report
GROUP BY r.gpu, r.driver, s.md5, s.renderer HAVING sessions >= 3
ORDER BY r.gpu, s.md5, drawn_fps DESC;
