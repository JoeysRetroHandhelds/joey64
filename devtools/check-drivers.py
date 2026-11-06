"""check-drivers.py: reports GitHub releases of our Vulkan drivers newer than the ones the app bundles.

Reads devtools/drivers/bundled.json. For each driver, the newest release of its repo whose name contains 'match'
is compared with the bundled 'tag' (watch_only entries: with 'seen'); tags listed in a driver's 'skip' (tag: reason)
were looked at and passed over and count as current. Prints one line per driver and exits 1 when
any has a newer release, 0 when all are current, 2 when GitHub could not be reached. Standard library only;
GITHUB_TOKEN is used when set (60 unauthenticated requests an hour is plenty otherwise).
--issues (the daily workflow, .github/workflows/driver-updates.yml): opens one issue per new release in
GITHUB_REPOSITORY, assigned to its owner, unless an issue with that title exists; --dry-run prints them instead.
Usage: python devtools/check-drivers.py [--json] [--issues [--dry-run]]
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def api(path, data=None):
    req = urllib.request.Request("https://api.github.com" + path, data=json.dumps(data).encode() if data else None,
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "joey64-driver-check"})
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def releases(repo):
    return api(f"/repos/{repo}/releases?per_page=30")


def open_issues(results, dry_run):
    repo = os.environ.get("GITHUB_REPOSITORY", "JoeysRetroHandhelds/joey64")
    owner = repo.split("/")[0]
    existing = set()
    if not dry_run:
        for page in range(1, 4):
            batch = api(f"/repos/{repo}/issues?state=all&per_page=100&page={page}")
            existing |= {i["title"] for i in batch}
            if len(batch) < 100:
                break
    for r in results:
        if not r.get("new"):
            continue
        title = f"Driver update: {r['name']} {r['latest']}"
        if title in existing:
            print(f"already reported: {title}")
            continue
        body = "\n".join([
            f"{r['title']} was published {r['published'][:10]} in {r['repo']}.",
            "",
            f"We bundle {r['have']}{'' if r['bundled'] else ' (watched only, not bundled)'}.",
            f"Release: {r['url']}",
            "",
            "Before bundling: compat run on the device it is for, then update devtools/drivers/bundled.json.",
        ])
        if dry_run:
            print(f"would open: {title}\n{body}\n")
        else:
            api(f"/repos/{repo}/issues", {"title": title, "body": body, "assignees": [owner]})
            print(f"opened: {title}")


def newest(repo, match):
    for rel in releases(repo):  # newest first
        if rel.get("draft") or match not in (rel.get("name") or rel.get("tag_name") or ""):
            continue
        return rel
    return None


def main():
    with open(os.path.join(HERE, "drivers", "bundled.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    entries = [(d, d["tag"], True) for d in cfg["drivers"]] + [(w, w["seen"], False) for w in cfg.get("watch_only", [])]
    results, new = [], False
    for d, have, bundled in entries:
        try:
            rel = newest(d["repo"], d.get("match", ""))
        except Exception as e:  # network, rate limit
            print(f"{d['name']}: could not check {d['repo']} ({e})")
            return 2
        if rel is None:
            results.append({"name": d["name"], "status": "no release found", "repo": d["repo"]})
            continue
        skipped = rel["tag_name"] in d.get("skip", {})  # looked at and passed over (reason in bundled.json)
        is_new = rel["tag_name"] != have and not skipped
        new |= is_new
        results.append({"name": d["name"], "repo": d["repo"], "have": have, "latest": rel["tag_name"],
                        "title": rel.get("name"), "published": rel.get("published_at"), "url": rel.get("html_url"),
                        "new": is_new, "bundled": bundled, "skipped": skipped})
    if "--issues" in sys.argv:
        open_issues(results, "--dry-run" in sys.argv)
    if "--json" in sys.argv:
        print(json.dumps(results, indent=2))
    else:
        for r in results:
            if "latest" not in r:
                print(f"{r['name']}: {r['status']} ({r['repo']})")
            elif r["new"]:
                print(f"NEW  {r['name']}: {r['title']} ({r['latest']}, {r['published'][:10]}), we have {r['have']}  {r['url']}")
            elif r.get("skipped"):
                print(f"skip {r['name']}: {r['latest']} passed over (see 'skip' in bundled.json), we have {r['have']}")
            else:
                print(f"ok   {r['name']}: {r['latest']} is current")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
