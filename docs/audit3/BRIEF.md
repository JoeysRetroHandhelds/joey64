# Joey64 code audit 3: shared brief

Joey64 is an Android N64 emulator for handhelds: a Kotlin app (launcher + game screen) hosting the
mupen64plus-next libretro core through our own C++ libretro frontend. Read
C:\GithubProjects\joey64-launcher\CLAUDE.md first: it describes every feature and file.

Code under audit (read only, change nothing, commit nothing, switch no branch):
- App: C:\GithubProjects\joey64-launcher (a git worktree, branch launcher/phase0, commit df01a53).
- Core: C:\GithubProjects\joey64-core, branch ra-known2 (commit 1281dd8, the bundled one). Read it with
  `git -C C:/GithubProjects/joey64-core show/diff/log ...` against `ra-known2`; do not check out anything,
  another session uses that checkout. Upstream base: merge-base 15bada85 (libretro/mupen64plus-libretro-nx).

This is a release audit: the whole code in your part, not only recent changes.

Look for, in this order of weight:
1. Crashes and hangs (null/!! on untrusted data, index errors, exceptions not caught on a path that runs
   in the background, ANRs: disk or network work on the main thread, deadlocks).
2. Threads and locks: emulation thread vs UI thread, the core option map (must only be written between
   frames), between-frame requests, shared state without a lock or @Volatile, coroutine scope leaks.
3. Lost or overwritten user data: saves (.srm, disk saves), save states, backups, data folder moves, SAF
   folders, restore, settings files (partial writes, non-atomic replace, wrong name, deleting before the
   new copy is in place, two writers).
4. Untrusted input: netplay packets, the updater feed and APK check, cheat lists, box-art and
   RetroAchievements replies, backup zips (zip slip, sizes), SAF folders, ROM headers and archives,
   intents from other apps (exported components), file names from outside (../).
5. Secrets in logs or reports: the ScreenScraper login (scrambled in BuildConfig, never to be logged or
   put in a URL that is logged), the RetroAchievements token/password, personal paths.
6. Leaks (windows, receivers, file handles, native memory, threads) and dead, debug-only or dev-only code
   that ships in a release build.

Rules:
- Every finding must be checked against the code: quote the lines, follow the call path, and say why it
  really happens (who calls it, on which thread, with what input). Drop anything you can't back up. No
  style nits, no "consider refactoring".
- Rank: High (crash, hang, data loss, security), Medium (wrong behaviour a user can hit, leak), Low
  (rare edge, robustness).
- For each: severity, file:line, what goes wrong and how a user or input gets there, the evidence,
  and a suggested fix (one or two sentences).
- Also list what you checked and found fine (one line each), so the report shows coverage.

Write your report to the file named in your task (Markdown), then reply with a short summary: the
number of findings by severity and the one-line title of each High and Medium.
