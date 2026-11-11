# Runs the Worker on this PC (wrangler dev --local, a local D1) on port 8798 and leaves it running, for the app:
#   adb reverse tcp:8798 tcp:8798, then files/dev_rooms_base = http://127.0.0.1:8798 (debug builds).
# LOCAL_TEST=1 skips the poster-address check (posts arrive from 127.0.0.1). Never set it in wrangler.toml.
# Needs wrangler: WRANGLER=path/to/wrangler sh test/local-run.sh (default: the finance project's copy).
cd "$(dirname "$0")/.."
W=${WRANGLER:-/c/GithubProjects/finance/server/node_modules/.bin/wrangler}
export WRANGLER_SEND_METRICS=false
rm -rf .wrangler/state
$W d1 execute joey64-rooms --local --file schema.sql > /dev/null 2>&1 || { echo "schema failed"; exit 1; }
exec $W dev --local --port 8798 --ip 127.0.0.1 --var LOCAL_TEST:1
