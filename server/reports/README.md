# Joey64 report server

A Cloudflare Worker with a D1 database. Joey64 installs whose user turned reports on send what they saw:
how each game ran on their GPU and driver, games with no cover or a wrong one, and crashes. Nothing is
sent back. The point (Joey, 2026-11-07): learn the best drivers and settings per GPU and game, find
covers to make, and see crashes we never meet ourselves.

Live since 2026-11-08 at https://joey64-reports.joeysretrohandhelds.workers.dev, in the Joey64
Cloudflare account (JoeysRetroHandhelds, its own account, not Joey's personal one; `account_id` in
`wrangler.toml` keeps deploys there). The app side (built 2026-11-08, approved mockup version A):
Settings › About › Send reports, Off by default, asked once over the library; see the app's
`reports/` package and `docs/launcher-build-plan.md` › Reports.
Crash `where`: core (native crash), host (Java crash or ANR while a game ran), launcher (otherwise).

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
      "seconds": 1800, "emuFps": 60, "drawnFps": 30, "dupes": 30, "underruns": 0, "ended": "quit",
      "frames": 54000, "firstFrameMs": 2310, "framesTimed": 108000, "p95Ms": 9, "p99Ms": 14, "slowFrames": 3,
      "lagMs": 41.2, "lagMaxMs": 66, "thermalMax": 1, "headroomMax": 0.62, "thermalDrops": 0,
      "rdpSync": false, "cpuCore": "dynarec", "refreshHz": 120, "cheats": 0, "fromState": true },
    { "type": "nocover", "md5": "...", "header": "NSME", "title": "file name without extension" },
    { "type": "replaced", "md5": "...", "cover": "screenscraper:<game>:<region>", "title": "..." },
    { "type": "crash", "md5": "...", "where": "core", "summary": "first lines, at most 2,000 characters" }
  ]
}
```

The session fields from `frames` on came with the 2026-11-08 app (`schema.sql` explains each); every one
may be missing. `underruns` is a count for the session. A crash `summary` for where `core` is the tombstone
read: signal, fault address, abort message, the crashing thread's frames (library names, offsets, functions).

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

- `schema.sql`: the tables (reports, sessions, covers, crashes), as a new database gets them.
- `migrations/`: changes for a database made before them, each run once (`0002-session-details.sql`: the
  session details, 2026-11-08). Run a migration before deploying the Worker that writes its columns.
- `src/index.js`: the Worker (handlers only: a Worker's main module may export nothing else).
- `src/validate.js`, `src/store.js`: checking and storing.
- `queries/`: `missing-covers.sql` (covers to make for joey64-data), `replaced-covers.sql` (wrong
  downloads, for the app's `fixes.tsv`), `drivers.sql` (fps, underruns and crashes per GPU, driver,
  game and renderer, 3 sessions or more), `failed-boots.sql`, `crashes.sql`.
- `test/`: `npm test` (node --test, a fake D1), and `sh test/local-run.sh` (wrangler dev with a local
  D1: posts samples, prints what was stored).

## Wrangler login

This PC's default wrangler login is another account. Joey64's login lives in its own folder, so set it
for every wrangler command here (Git Bash):

```
export XDG_CONFIG_HOME='C:\Users\Joey\.joey64-cloudflare'
wrangler whoami   # should say the JoeysRetroHandhelds account
```

Run without it, `account_id` in `wrangler.toml` makes the command fail instead of touching the other account.

## Run a query

```
wrangler d1 execute joey64-reports --remote --file queries/missing-covers.sql
```

`--local` runs it on the database `test/local-run.sh` made.

## Deploy

`wrangler deploy`, after `npm test` and `sh test/local-run.sh`. Schema changes: new tables and indexes go in
`schema.sql` as steps that can run twice (`IF NOT EXISTS`); new columns go in `schema.sql` and a new
`migrations/` file, which is run once with `--remote` before the deploy.

A report is one request and writes 1 + its events rows: check those against the account's plan limits
for Workers and D1 before turning reports on for everyone.
