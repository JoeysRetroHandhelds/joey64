"""check-drivers.py: reports GitHub releases of our Vulkan drivers newer than the ones the app bundles.

Reads devtools/drivers/bundled.json. For each driver, the newest release of its repo whose name contains 'match'
is compared with the bundled 'tag' (watch_only entries: with 'seen'). Prints one line per driver and exits 1 when
any has a newer release, 0 when all are current, 2 when GitHub could not be reached. Standard library only;
GITHUB_TOKEN is used when set (60 unauthenticated requests an hour is plenty otherwise).
Usage: python devtools/check-drivers.py [--json]
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def releases(repo):
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases?per_page=30",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "n64-handheld-driver-check"})
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


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
        is_new = rel["tag_name"] != have
        new |= is_new
        results.append({"name": d["name"], "repo": d["repo"], "have": have, "latest": rel["tag_name"],
                        "title": rel.get("name"), "published": rel.get("published_at"), "url": rel.get("html_url"),
                        "new": is_new, "bundled": bundled})
    if "--json" in sys.argv:
        print(json.dumps(results, indent=2))
    else:
        for r in results:
            if "latest" not in r:
                print(f"{r['name']}: {r['status']} ({r['repo']})")
            elif r["new"]:
                print(f"NEW  {r['name']}: {r['title']} ({r['latest']}, {r['published'][:10]}), we have {r['have']}  {r['url']}")
            else:
                print(f"ok   {r['name']}: {r['latest']} is current")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
