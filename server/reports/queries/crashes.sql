-- Crashes by where and first line, most installs first.
SELECT cr.where_, substr(cr.summary, 1, instr(cr.summary || char(10), char(10)) - 1) AS first_line, cr.md5,
       count(DISTINCT r.install) AS installs, max(r.day) AS last_seen, max(r.gpu) AS a_gpu
FROM crashes cr JOIN reports r ON r.id = cr.report
GROUP BY cr.where_, first_line, cr.md5 ORDER BY installs DESC, last_seen DESC;
