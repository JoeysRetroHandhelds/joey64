"""check-upstreams.py: lists new commits in the projects our core is built from (devtools/upstreams.json).

For each source, the commits on its branch newer than 'seen' (only those touching 'path' when it is set) are listed.
Exits 1 when any source has new commits, 0 when none, 2 when GitHub could not be reached. Standard library only;
GITHUB_TOKEN is used when set.
--issues (the daily workflow, .github/workflows/upstream-updates.yml): keeps one open issue per source in
GITHUB_REPOSITORY, titled "Upstream: <name>", assigned to the repo owner. A new issue lists every commit since 'seen';
later days add a comment with only the commits since the last one reported (marker <!-- last: sha --> in the issue
body or comments). Close the issue after merging or reading them and move 'seen' forward. --dry-run prints instead.
Usage: python devtools/check-upstreams.py [--issues [--dry-run]]
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MARK = re.compile(r"<!-- last: ([0-9a-f]{7,40}) -->")


def api(path, data=None, method=None):
    req = urllib.request.Request("https://api.github.com" + path, data=json.dumps(data).encode() if data else None,
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "joey64-upstream-check"},
                                 method=method)
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def commits_since(src, since_sha):
    """Newest first, stopping at since_sha (not included). At most 300 commits."""
    out = []
    q = {"sha": src["branch"], "per_page": "100"}
    if src.get("path"):
        q["path"] = src["path"]
    for page in range(1, 4):
        q["page"] = str(page)
        batch = api(f"/repos/{src['repo']}/commits?" + urllib.parse.urlencode(q))
        for c in batch:
            if c["sha"].startswith(since_sha):
                return out, True
            out.append(c)
        if len(batch) < 100:
            break
    return out, False  # since_sha not found (history rewritten, or older than 300 commits)


def lines(src, commits, found):
    rows = [f"- {c['commit']['committer']['date'][:10]} [`{c['sha'][:7]}`]({c['html_url']}) "
            f"{c['commit']['message'].splitlines()[0][:120]}" for c in commits]
    if not found:
        rows.append("- (the starting commit was not found in the last 300; the list may be incomplete)")
    return rows


def report_issues(results, dry_run):
    repo = os.environ.get("GITHUB_REPOSITORY", "JoeysRetroHandhelds/joey64")
    owner = repo.split("/")[0]
    open_issues = {}
    if not dry_run:
        for page in range(1, 4):
            batch = api(f"/repos/{repo}/issues?state=open&per_page=100&page={page}")
            for i in batch:
                if i["title"].startswith("Upstream: ") and "pull_request" not in i:
                    open_issues[i["title"]] = i
            if len(batch) < 100:
                break
    for src, commits, found in results:
        if not commits:
            continue
        title = f"Upstream: {src['name']}"
        issue = open_issues.get(title)
        new, ok = commits, found
        if issue:
            texts = [issue.get("body") or ""] + [c["body"] for c in api(f"/repos/{repo}/issues/{issue['number']}/comments?per_page=100")]
            last = next((m.group(1) for t in reversed(texts) for m in reversed(list(MARK.finditer(t)))), None)
            if last:
                new = []
                for c in commits:
                    if c["sha"].startswith(last):
                        break
                    new.append(c)
                ok = True
            if not new:
                print(f"{title}: nothing new since the last report")
                continue
        where = f"`{src['repo']}` {src['branch']}" + (f", `{src['path']}`" if src.get("path") else "")
        head = [f"{len(new)} new commit(s) in {where}:", ""] if issue else [
            f"New commits in {where} since the one we last merged or read (`{src['seen'][:7]}`). {src.get('why', '')}", ""]
        body = "\n".join(head + lines(src, new, ok) + ["", f"<!-- last: {new[0]['sha']} -->"])
        if not issue:
            body += "\n\nAfter merging or reading them: set `seen` in devtools/upstreams.json to the newest and close this issue."
        if dry_run:
            print(f"would {'comment on' if issue else 'open'}: {title}\n{body}\n")
        elif issue:
            api(f"/repos/{repo}/issues/{issue['number']}/comments", {"body": body})
            print(f"commented: {title} ({len(new)})")
        else:
            api(f"/repos/{repo}/issues", {"title": title, "body": body, "assignees": [owner]})
            print(f"opened: {title} ({len(new)})")


def main():
    with open(os.path.join(HERE, "upstreams.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    results, any_new = [], False
    for src in cfg["sources"]:
        try:
            commits, found = commits_since(src, src["seen"])
        except Exception as e:  # network, rate limit
            print(f"{src['name']}: could not check {src['repo']} ({e})")
            return 2
        results.append((src, commits, found))
        any_new |= bool(commits)
        if commits:
            print(f"NEW  {src['name']}: {len(commits)} commit(s), newest {commits[0]['commit']['committer']['date'][:10]} "
                  f"{commits[0]['commit']['message'].splitlines()[0][:80]}")
        else:
            print(f"ok   {src['name']}: nothing after {src['seen'][:7]}")
    if "--issues" in sys.argv:
        report_issues(results, "--dry-run" in sys.argv)
    return 1 if any_new else 0


if __name__ == "__main__":
    sys.exit(main())
