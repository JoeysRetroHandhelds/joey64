-- Downloaded covers users replaced with their own: likely wrong ones, for fixes.tsv.
SELECT c.md5, c.cover, max(c.title) AS title, count(DISTINCT r.install) AS installs
FROM covers c JOIN reports r ON r.id = c.report
WHERE c.kind = 'replaced'
GROUP BY c.md5, c.cover ORDER BY installs DESC;
