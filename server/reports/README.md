# Joey64 report server

A Cloudflare Worker with a D1 database. Joey64 installs whose user turned reports on send what they saw:
how each game ran on their GPU and driver, games with no cover or a wrong one, and crashes. Nothing is
sent back. The point (Joey, 2026-11-07): learn the best drivers and settings per GPU and game, find
covers to make, and see crashes we never meet ourselves.

Not deployed yet. The app doesn't send anything yet either: the opt-in question and the Settings row
wait for Joey's design.

## What a report holds

`POST /v1/report`, JSON, at most 64 KB and 200 events:

```json
{
  "format": 1,
  "install": "<random uuid v4, made by the app, the user can reset it>",
  "app": "0.9.0", "core": "0ac9d17",
  "device": { "maker": "AYN", "model": "Thor", "soc": "SM8550", "gpu": "Adreno 740",
              "driver": "Turnip Gen8 V38", "android": 34 },
  "events": [
    { "type": "session", "md5": "<rom md5>", "header": "NSME", "renderer": "parallel", "scale": 1,
      "settings": { "only": "the ones that differ from the defaults" },
      "seconds": 1800, "emuFps": 60, "drawnFps": 30, "dupes": 30, "underruns": 0, "ended": "quit" },
    { "type": "nocover", "md5": "...", "header": "NSME", "title": "file name without extension" },
    { "type": "replaced", "md5": "...", "cover": "screenscraper:<game>:<region>", "title": "..." },
    { "type": "crash", "md5": "...", "where": "core", "summary": "first lines, at most 2,000 characters" }
  ]
}
```

`src/validate.js` checks every field and rejects the whole report on anything unexpected. Answers:
204 stored, 400 bad report, 413 too big, 429 too many (10 posts a minute per IP address, 6 reports a day
per install), 404/405 anything else. `GET /health` says `ok`.

## What is kept out

- No IP address: the rate limiter counts by it, nothing stores it.
- No time finer than the day (UTC) a report arrived.
- No account names (RetroAchievements or other), no folder or file paths, no ROM data. A title is the
  file name without its folder.
- The install id is random, not tied to the device, and the user can reset it.

## Files

- `schema.sql`: the tables (reports, sessions, covers, crashes).
- `src/index.js`: the Worker (handlers only: a Worker's main module may export nothing else).
- `src/validate.js`, `src/store.js`: checking and storing.
- `queries/`: `missing-covers.sql` (covers to make for joey64-data), `replaced-covers.sql` (wrong
  downloads, for the app's `fixes.tsv`), `drivers.sql` (fps, underruns and crashes per GPU, driver,
  game and renderer, 3 sessions or more), `crashes.sql`.
- `test/`: `npm test` (node --test, a fake D1), and `sh test/local-run.sh` (wrangler dev with a local
  D1: posts samples, prints what was stored).

## Run a query

```
wrangler d1 execute joey64-reports --remote --file queries/missing-covers.sql
```

`--local` runs it on the database `test/local-run.sh` made.

## Deploy (ask Joey first: it creates resources in their Cloudflare account)

1. `wrangler d1 create joey64-reports`, put the id it prints in `wrangler.toml` (`database_id`).
2. `wrangler d1 execute joey64-reports --remote --file schema.sql`
3. `wrangler deploy`. The address is `https://joey64-reports.<account subdomain>.workers.dev`.

A report is one request and writes 1 + its events rows: check those against the account's plan limits
for Workers and D1 before turning reports on for everyone.
