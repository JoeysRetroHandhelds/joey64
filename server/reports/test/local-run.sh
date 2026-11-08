# Runs the Worker on this PC (wrangler dev --local, a local D1), posts sample reports, prints what was stored.
# Needs wrangler: WRANGLER=path/to/wrangler sh test/local-run.sh (default: the finance project's copy).
cd "$(dirname "$0")/.."
W=${WRANGLER:-/c/GithubProjects/finance/server/node_modules/.bin/wrangler}
export WRANGLER_SEND_METRICS=false
rm -rf .wrangler/state
$W d1 execute joey64-reports --local --file schema.sql > /dev/null 2>&1 || { echo "schema failed"; exit 1; }
$W dev --local --port 8799 --ip 127.0.0.1 > .wrangler-dev.log 2>&1 &
DEV=$!
for i in $(seq 1 40); do curl -s -m 2 http://127.0.0.1:8799/health 2>/dev/null | grep -q ok && break; sleep 2; done
curl -s -m 5 http://127.0.0.1:8799/health || { echo "worker didn't start"; tail -20 .wrangler-dev.log; kill $DEV; exit 1; }
echo
node -e '
import("./test/fixture.js").then(async ({ good }) => {
  const post = (b) => fetch("http://127.0.0.1:8799/v1/report", { method: "POST", headers: { "content-type": "application/json" }, body: typeof b === "string" ? b : JSON.stringify(b) });
  for (const [n, b] of [["good", good()], ["bad json", "{x"], ["bad md5", { ...good(), events: [{ type: "nocover", md5: "zz" }] }]]) {
    const r = await post(b); console.log(n, r.status, (await r.text()).slice(0, 60));
  }
  const codes = []; for (let i = 0; i < 12; i++) codes.push((await post(good())).status); console.log("repeats", codes.join(" "));
})'
kill $DEV 2>/dev/null; sleep 2
for t in reports sessions covers crashes; do
  echo "$t: $($W d1 execute joey64-reports --local --json --command "select count(*) n from $t" 2>/dev/null | grep -o '"n": [0-9]*')"
done
$W d1 execute joey64-reports --local --json --command "select md5, kind, title, cover from covers limit 2" 2>/dev/null | grep -E '"(md5|kind|title|cover)"'
