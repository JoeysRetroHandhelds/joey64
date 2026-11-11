# joey64-rooms

The worldwide list of public Netplay rooms (Joey 2026-11-10: "Our Cloudflare Worker"): a Cloudflare Worker with a
D1 database, in the Joey64 account like `server/reports`. **Not deployed yet**: the deploy and the D1 database
need Joey's go (`wrangler.toml` still has a placeholder `database_id`).

Only public rooms that can be reached from anywhere are listed. Private rooms are never sent here; rooms on the
same Wi-Fi are found by the app's own UDP beacons, not through this Worker.

## API

- `GET /rooms`: the fresh rooms (seen in the last 75 s), newest first, without their secrets.
- `POST /rooms` (JSON `id`, `secret`, `name`, `device`, `game`, `md5`, `players`, `code`): lists or refreshes a
  room. The app posts every 30 s while its room is public. The room code's IP must be the poster's own IPv4
  (`cf-connecting-ip`) and not a private address. A wrong secret for a known id gets 403; more than 4 rooms
  from one address 429; a full list (300) 503. A new room with the same code replaces the old one.
- `DELETE /rooms/<id>` with header `X-Room-Secret`: removes the room (the host closed it or started the game).
- `GET /health`.

Text fields lose control and bidi characters and are cut (name and device 48, game 80). Rate limits:
`POSTS` 10 a minute and `READS` 20 a minute per address (`wrangler.toml`).

## Files

- `src/index.js`: the handler (it exports only `default`: the Workers runtime refuses other named exports).
- `src/rules.js`: validation, limits, private-address check. `src/code.js`: room codes, as `RoomCode.kt`.
- `schema.sql`: the `rooms` table.
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

## Deploying (after Joey's go)

```
wrangler d1 create joey64-rooms          # put the id in wrangler.toml
wrangler d1 execute joey64-rooms --remote --file schema.sql
wrangler deploy
```
