# Runs the Worker on this PC (wrangler dev --local: local D1 and R2) and posts states to POST /v1/state, with the
# wanted list served from joey64-data on this PC. Prints the answers, the rows and the stored files.
# WRANGLER=path/to/wrangler sh test/local-state.sh (default: the finance project's copy);
# WANTED=path/to/wanted.json (default: C:/GithubProjects/joey64-data/wanted/wanted.json).
cd "$(dirname "$0")/.."
W=${WRANGLER:-/c/GithubProjects/finance/server/node_modules/.bin/wrangler}
WANTED=${WANTED:-C:/GithubProjects/joey64-data/wanted/wanted.json}
export WRANGLER_SEND_METRICS=false
rm -rf .wrangler/state
$W d1 execute joey64-reports --local --file schema.sql > /dev/null 2>&1 || { echo "schema failed"; exit 1; }
python -I -m http.server 8834 --bind 127.0.0.1 --directory "$(dirname "$WANTED")" > /dev/null 2>&1 &
WEB=$!
$W dev --local --port 8799 --ip 127.0.0.1 --var WANTED_URL:http://127.0.0.1:8834/$(basename "$WANTED") > .wrangler-dev.log 2>&1 &
DEV=$!
for i in $(seq 1 40); do curl -s -m 2 http://127.0.0.1:8799/health 2>/dev/null | grep -q ok && break; sleep 2; done
curl -s -m 5 http://127.0.0.1:8799/health > /dev/null || { echo "worker didn't start"; tail -20 .wrangler-dev.log; kill $DEV $WEB; exit 1; }
MD5=$(python -I -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['games'][0]['md5'])" "$WANTED")
node -e '
const zlib = require("node:zlib");
const md5 = process.argv[1];
const rzip = () => { const b = Buffer.alloc(300000, 7); Buffer.from("#RZIPv\x01#", "latin1").copy(b); return b; };
const post = async (f) => {
  const fd = new FormData();
  for (const [k, v] of Object.entries(f)) { if (Buffer.isBuffer(v)) fd.append(k, new Blob([v]), k); else fd.append(k, v); }
  const r = await fetch("http://127.0.0.1:8799/v1/state", { method: "POST", body: fd });
  return r.status + " " + (r.headers.get("retry-after") ? "retry-after " + r.headers.get("retry-after") + " " : "") + (await r.text()).slice(0, 40);
};
(async () => {
  const good = { md5, kind: "gameplay", app: "0.2.0.1", state: rzip(), save: zlib.gzipSync(Buffer.alloc(0x48800)) };
  console.log("wanted game  ", await post(good));
  console.log("not wanted   ", await post({ ...good, md5: "f".repeat(32) }));
  console.log("wrong kind   ", await post({ ...good, kind: "problem" }));
  console.log("not RZIP     ", await post({ ...good, state: Buffer.alloc(4096) }));
  console.log("save not gz  ", await post({ ...good, save: Buffer.alloc(300) }));
  const codes = []; for (let i = 0; i < 5; i++) codes.push((await post(good)).split(" ")[0]);
  console.log("5 more (enough 5):", codes.join(" "));
})();' "$MD5"
kill $DEV $WEB 2>/dev/null; sleep 2
echo "states rows: $($W d1 execute joey64-reports --local --json --command "select count(*) n from states" 2>/dev/null | grep -o '"n": [0-9]*')"
$W d1 execute joey64-reports --local --json --command "select day, md5, kind, app, state_bytes, save_bytes, who is not null as has_who, r2_key from states limit 1" 2>/dev/null | grep -E '"(day|md5|kind|state_bytes|save_bytes|has_who|r2_key)"'
echo "R2 files: $(find .wrangler/state -path '*r2*' -type f 2>/dev/null | grep -v -E 'sqlite|\.db' | wc -l)"
