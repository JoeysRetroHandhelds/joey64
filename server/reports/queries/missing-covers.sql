-- Games reported with no cover, most installs first: the list to make covers for (joey64-data boxart/, named <md5>.png).
SELECT c.md5, max(c.header) AS header, max(c.title) AS title, count(DISTINCT r.install) AS installs, max(r.day) AS last_seen
FROM covers c JOIN reports r ON r.id = c.report
WHERE c.kind = 'none'
GROUP BY c.md5 ORDER BY installs DESC, last_seen DESC;
