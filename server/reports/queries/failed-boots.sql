-- Games that didn't boot: 5 s or more played and fewer than 60 frames drawn (from 2026-11-08's app on),
-- per game, GPU, driver and renderer, with the crashes among them.
SELECT s.md5, s.header, r.gpu, r.driver, s.renderer, s.cpu_core, s.rdp_sync, count(*) AS sessions,
       count(DISTINCT r.install) AS installs, sum(s.ended = 'crash') AS crashes, max(s.first_frame_ms) AS first_frame_ms
FROM sessions s JOIN reports r ON r.id = s.report
WHERE s.frames IS NOT NULL AND s.frames < 60
GROUP BY s.md5, r.gpu, r.driver, s.renderer
ORDER BY installs DESC, sessions DESC;
