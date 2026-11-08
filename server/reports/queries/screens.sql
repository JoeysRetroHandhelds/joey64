-- Screens by device: pixels, density and the dp size layouts see (ScreenLayout), with how many installs sent each.
SELECT maker, model, screen_w, screen_h, screen_dpi,
       ROUND(screen_w * 160.0 / screen_dpi) AS dp_w, ROUND(screen_h * 160.0 / screen_dpi) AS dp_h,
       COUNT(DISTINCT install) AS installs
FROM reports
WHERE screen_w IS NOT NULL AND screen_dpi > 0
GROUP BY maker, model, screen_w, screen_h, screen_dpi
ORDER BY installs DESC;
