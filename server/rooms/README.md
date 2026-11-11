# joey64-rooms

The worldwide list of public Netplay rooms (Joey 2026-11-10: "Our Cloudflare Worker"): a Cloudflare Worker with a
D1 database, in the Joey64 account like `server/reports`. Deployed 2026-11-10 (Joey's go) at
`https://joey64-rooms.joeysretrohandhelds.workers.dev`, D1 `joey64-rooms` (region WNAM).

Only public rooms that can be reached from anywhere are listed. Private rooms are never sent here; rooms on the
same Wi-Fi are found by the app's own UDP beacons, not through this Worker.

## API

- `GET /rooms`: the fresh rooms (seen in the last 75 s), newest first, without their secrets.
- `POST /rooms` (JSON `id`, `secret`, `name`, `device`, `game`, `md5`, `players`, `code`): lists or refreshes a
  room. The app posts every 30 s while its room is public. The room code's IP must be the poster's own IPv4
  (`cf-connecting-ip`) and not a private address. A wrong secret for a known id gets 403; more than 4 rooms
  from one address 429; a full list (300) 503. A new room with the same code, posted from that address,
  replaces the old one.
- A post over IPv6 (a dual-stack phone or home network) can't show that the code's IPv4 is its own, so since
  code audit 3 (M24, not deployed yet) such a room: never replaces another room's code (409 "code in use");
  gets one room per /64 (429); and shares 50 places in all with the other IPv6-posted rooms (503), so they can't
  fill the list. To count one per /64 without keeping the address, the room keeps a hash of its /64 with a
  salt made for the day (`via`, table `salts`; the salt goes the next day, the hash with the room). A room
  posted over IPv4 from its code's address is `verified`, and stays so while its code stays the same.
- `DELETE /rooms/<id>` with header `X-Room-Secret`: removes the room (the host closed it or started the game).
- `GET /health`.

Text fields lose control and bidi characters and are cut (name and device 48, game 80). Rate limits:
`POSTS` 10 a minute and `READS` 20 a minute per address, an IPv6 address by its /64 (`wrangler.toml`).

## Files

- `src/index.js`: the handler (it exports only `default`: the Workers runtime refuses other named exports).
- `src/rules.js`: validation, limits, private-address check. `src/code.js`: room codes, as `RoomCode.kt`.
  `src/address.js`: IPv6 /64s and their hashes (a copy of `server/reports/src/address.js`).
- `schema.sql`: the `rooms` and `salts` tables. `migrations/0002-unverified.sql`: the columns code audit 3 added,
  for the database made before them (run once).
- `test/worker.test.js`: `npm test` (node:sqlite stands in for D1).
- `test/local-run.sh`: `wrangler dev --local` on port 8798 with `LOCAL_TEST=1` (skips the address checks,
  since posts arrive from 127.0.0.1). Never set `LOCAL_TEST` in `wrangler.toml`.
- `test/seed-rooms.mjs`: posts three made-up rooms every 30 s to the local Worker.

## Checking with the app (debug builds)

```
sh test/local-run.sh
node test/seed-rooms.mjs
adb reverse tcp:8798 tcp:8798
adb shell run-as com.joeysretro.joey64 sh -c 'echo http://127.0.0.1:8798 > files/dev_rooms_base'
```

Remove `files/dev_rooms_base` and the reverse afterwards. Release builds use
`https://joey64-rooms.joeysretrohandhelds.workers.dev`.

## Deploying

This PC's default wrangler login is another account; Joey64's lives in its own folder (as `server/reports`):

```
export XDG_CONFIG_HOME='C:\Users\Joey\.joey64-cloudflare'
wrangler whoami   # the Joey64 account, f59e0cc0...
npm test
wrangler deploy
```

Schema changes: steps that can run twice in `schema.sql`, run with
`wrangler d1 execute joey64-rooms --remote --file schema.sql` before the deploy. New columns go in `schema.sql` and
a new `migrations/` file, run once with `--remote` before the deploy: the next deploy (code audit 3) needs
`wrangler d1 execute joey64-rooms --remote --file migrations/0002-unverified.sql` first.
