# Joey64 code audit 4, app half: shared brief

Joey64 is an Android N64 emulator for handhelds: a Kotlin app (launcher and game screen) that hosts the
mupen64plus-next libretro core through our own C++ libretro frontend. Read
C:\GithubProjects\joey64-launcher\CLAUDE.md first. It describes every feature and file.

This is the release audit before the first tester build. It is split between two sessions. This half covers all
the Kotlin, the UI, and the data files the app reads. The other half covers the native host (app/src/main/cpp),
the core, both Workers (server/reports, server/rooms) and the release config (gradle, signing, R8, manifest,
licences, drivers). Leave those to the other half, except the JNI contract: check the Kotlin side of every
`external fun` / native call you meet (argument order, types, nullability, which thread calls it, what happens
when it returns an error or throws).

Code under audit (read only: change nothing, commit nothing, switch no branch, run no gradle task):
- C:\GithubProjects\joey64-launcher, branch launcher/phase0, commit cca5848 (main merged in).
- Kotlin: app/src/main/java/com/joeysretro/joey64/. Unit tests: app/src/test/.
- Audit 3's report and fixes are in C:\GithubProjects\joey64-notes\docs\audit3\ (REPORT.md, FIXES.md, part*.md).
  Don't re-report an item listed there as fixed unless the fix is wrong or incomplete (then say so and quote it).
  Code added since audit 3 (commit df01a53) deserves extra care: `git -C C:/GithubProjects/joey64-launcher log
  --oneline df01a53..cca5848 -- <path>` shows what changed in your part.

This is a release audit: the whole code in your part, not only recent changes.

Look for, in this order of weight:
1. Crashes and hangs: null or !! on untrusted data, index errors, exceptions not caught on a background path,
   ANRs (disk or network work on the main thread), deadlocks.
2. Threads and locks: emulation thread vs UI thread, the core option map (written only between frames),
   between-frame requests, shared state without a lock or @Volatile, coroutine scope leaks.
3. Lost or overwritten user data: saves (.srm, disk saves), save states, backups, data folder moves, SAF folders,
   restore, settings files (partial writes, non-atomic replace, wrong name, deleting before the new copy is in
   place, two writers).
4. Untrusted input: netplay packets, the updater feed and APK check, cheat lists, box-art and RetroAchievements
   replies, the wanted list and server answers, backup zips (zip slip, sizes), SAF folders, ROM headers and
   archives, intents from other apps, file names from outside (../).
5. Secrets in logs or reports: the ScreenScraper login (scrambled in BuildConfig; never logged or put in a logged
   URL), the RetroAchievements token and password, netplay keys, personal paths.
6. Leaks (windows, receivers, file handles, threads, big files left on disk) and debug-only or dev-only code that
   ships in a release build (dev_* files, SHOW broadcasts, env overrides).
7. UI parts only: focus that can be lost or trapped (D-pad and TV remote), Back that does the wrong thing, touch
   that can't reach an action, text that clips or overflows at large font scale, missing content descriptions,
   strings that say something the code doesn't do.

Rules:
- Every finding must be checked against the code. Quote the lines, follow the call path, and say why it really
  happens: who calls it, on which thread, with what input. Drop anything you can't back up. No style nits, no
  "consider refactoring".
- Rank: High (crash, hang, data loss, security), Medium (wrong behaviour a user can hit, a leak), Low (rare edge,
  robustness).
- For each finding: severity, file:line, what goes wrong and how a user or input gets there, the evidence (quoted
  lines), and a suggested fix in one or two sentences.
- Also list what you checked and found fine, one line each, so the report shows coverage.
- Plain English: short sentences, no jargon a reader has to decode.

Write your part to the file named in your task, in this shape:

    # Audit 4 app part <letter>: <name>
    ## Findings
    ### H1 <one-line title>
    - Where: path:line
    - What happens: ...
    - Evidence: ...
    - Fix: ...
    (then M1.., L1..)
    ## Checked and fine
    - ...
