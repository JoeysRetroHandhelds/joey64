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
              "driver": "Turnip Gen8 V38", "android": 34,
              "screenW": 1920, "screenH": 1080, "dpi": 369 },
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
204 stored, 400 bad report, 413 too big, 429 too many (10 posts a minute per IP address, an IPv6 address by
its /64; 6 reports a day per install; 3,000 rows written or 1 MB posted a day per address block, an IPv4 /24
or an IPv6 /64; 80,000 rows written a day in all, under D1's free 100,000), 404/405 anything else.
`GET /health` says `ok`. The daily caps came with code audit 3 (M23): before them one address could post about
250 full reports in 25 minutes, use up D1's day, and every real report failed until 00:00 UTC.

## States (POST /v1/state)

Players' save states for games on joey64-data's wanted list (`wanted/wanted.json` on its `release` branch, read
every 10 minutes; `WANTED_URL` points it elsewhere for a local run), sent by the app's state-request popup
(2026-11-12). Multipart: `md5`, `kind` (gameplay / problem, as the list says), `app`, `state` (a RetroArch
state file, RZIP, 1 KB to 20 MB) and optionally `save` (the .srm gzipped, up to 512 KB). The files go to the private
R2 bucket `joey64-states` (`states/<md5>/<day>-<uuid>.state|.srm.gz`), never served; a `states` row counts each. Answers:
204 stored, 400 a bad field, 404 not wanted and 410 have enough (the app doesn't send that game again), 413 too
big, 429 with Retry-After (the rate limiter 60 s; 10 a day an address block, 200 a day in all: to UTC midnight;
8 GB stored in all: a day), 503 the wanted list didn't load. The address block's hash is kept for its day only.

## What is kept out

- No IP address: the rate limiter counts by it, nothing stores it. An address block's share of the day (the
  /24 or /64 above) is counted under a hash of the block and a random salt made for that day (tables `days`
  and `posters`); the next day's first post deletes those counts and the salt, so no stored hash can be
  matched to an address after its day.
- No time finer than the day (UTC) a report arrived.
- No account names (RetroAchievements or other), no folder or file paths, no ROM data. A title is the
  file name without its folder.
- The install id is random, not tied to the device, and the user can reset it.

## Files

- `schema.sql`: the tables (reports, sessions, covers, crashes; days and posters, the daily caps' counts), as a
  new database gets them.
- `migrations/`: changes for a database made before them, each run once (`0002-session-details.sql`: the
  session details, 2026-11-08; `0003-screen.sql`: the screen size, 2026-11-08; `0004-gpu-rules.sql`: Auto's driver and
  renderer rules, the start-up trial's level and who picked, 2026-11-09, read by `queries/rules.sql`; `0005-states.sql`: players' states, 2026-11-12). Run a migration before deploying the Worker that writes its columns.
- `src/index.js`: the Worker (handlers only: a Worker's main module may export nothing else).
- `src/validate.js`, `src/store.js`: checking and storing; `src/address.js`: address blocks and their hashes;
  `src/state.js`: POST /v1/state.
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

The daily caps (code audit 3, not deployed yet) need their two tables first:
`wrangler d1 execute joey64-reports --remote --file schema.sql` (the rest of it is already there and is left
as it is), then `wrangler deploy`.

States (not deployed yet, each step needs Joey's go): `wrangler r2 bucket create joey64-states`,
`wrangler d1 execute joey64-reports --remote --file migrations/0005-states.sql`, then `wrangler deploy`.

## Free plan limits (checked 2026-11-08, Cloudflare's pages of 2026-10-29/30)

| Limit (free) | What one report uses | Room |
|---|---|---|
| Workers: 100,000 requests a day (reset 00:00 UTC; over it, error 1027) | 1 request | 100,000 reports a day; an install sends 1 a day (3 at most) |
| Workers: 10 ms CPU a request | parsing and checking: a 263 KB report (4x the 64 KB cap) took 1.7 ms in node on JoeyPC | a full report is well inside it |
| D1: 100,000 rows written a day | 2 for the report (row + index), 2 a session or cover (md5 index), 1 a crash, 2 for the day's counts (`rowsOf`) | the Worker stops at 80,000 (about 10,000 reports a day at 2 sessions each); a first report with 150 covers writes about 300 once |
| D1: 5 million rows read a day | the per-install count uses its index: a few rows | not a concern |
| D1: 500 MB a database | about 450 bytes a report with one session (measured: 20,000 in SQLite, schema of 2026-11-08) | about 1.1 million sessions |
| D1: 50 queries a request | 3 (the count, the report row, one batch) | fine |

Database size is the limit that comes first: at 1,000 installs reporting 2 sessions a day it fills in
about 1.5 years, at 10,000 in about 2 months. Before that, roll old sessions up into per-game, GPU and
driver totals and delete the rows, or move to the paid plan. Over a daily limit, the Worker or D1 fails,
the app gets no 204 and sends the same reports the next day, so nothing is lost but a day.
