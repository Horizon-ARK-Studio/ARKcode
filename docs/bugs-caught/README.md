# BUGS-CAUGHT

> Active bug tracker.
> Bugs remain here until they are fixed, tested, and confirmed working.
> Once confirmed, they are removed from this file.
> This is just the template, create a new bug-name.md file and track the specific issue there.

---

## Active Bugs

<!--
Add unfixed bugs here.

Template:

### BUG-XXXX: Short description
- **Status:** `UNFIXED`
- **Found:** YYYY-MM-DD
- **Stage:** `v1 (Android) | v2 (desktop window mode) | v3 (desktop chrome mode)`
- **Location:** `path/to/file:line`
- **Severity:** `Critical | High | Medium | Low`
- **Description:**
  What is going wrong.

- **Expected:**
  What should happen.

- **Actual:**
  What happens instead.

- **Reproduction:**
  1. Step one
  2. Step two
  3. Step three

- **Likely cause:**
  Suspected cause, if known.

- **Fix:**
  What needs to be changed.

- **Test:**
  How the fix must be verified.

- **Notes:**
  Additional information.
-->

### BUG-0001: App crashes ~5-15s after launch when IdeBackendService fails to start
- **Status:** `FIXED (pending build/device verification)`
- **Found:** 2026-09-03
- **Stage:** `v1 (Android)`
- **Location:** `android-project/app/src/main/java/com/arkware/ide/backend/IdeBackendService.kt:157` (`launchNode`), `android-project/app/src/main/java/com/arkware/ide/MainActivity.kt:262` (`startAndBindBackend`)
- **Severity:** `Critical`

- **Description:**
  `MainActivity.startAndBindBackend()` calls `ContextCompat.startForegroundService()`,
  which obligates the service to call `Service.startForeground()` within
  a few seconds. `IdeBackendService.launchNode()` only called
  `promoteToForeground()` (which calls `startForeground()`) after the
  Node process reported a bound port on stdout. Every failure path in
  `launchNode()` (missing `libnode.so`, missing `code-server` server
  bundle, exec failure) returned via `fail()` before ever reaching that
  point.

- **Expected:**
  The app stays alive and shows a clear failure state when the backend
  can't start.

- **Actual:**
  The OS kills the whole app process with
  `ForegroundServiceDidNotStartInTimeException: Context.startForegroundService()
  did not then call Service.startForeground()`, which surfaces to the
  user as an unexplained crash ~5-15 seconds after launch.

- **Reproduction:**
  1. Build and install this checkout as-is (only the workbench frontend
     is vendored; `assets/code-server/out/node/entry.js` does not
     exist).
  2. Launch the app.
  3. Wait ~5-15 seconds -- the app crashes.

- **Likely cause:**
  `startForeground()` was only reachable from the success path
  (`watchStdoutForPort()` finding a port), so any early failure in
  `launchNode()` left the service's foreground promise unfulfilled.

- **Fix:**
  `launchNode()` now calls `promoteToForegroundStarting()` (a new method
  that calls `startForeground()` with an "indeterminate" notification)
  as its first statement, before any of the checks that can fail.
  `fail()` now calls `stopForeground(true)` once that promise is
  already satisfied, since there is nothing left running to keep a
  persistent notification for. `IdeBackendState.Failed` is also now
  surfaced in the WebView itself (see `MainActivity.onBackendState()` /
  `assets/index.html`'s `#ide-failed-screen` / `assets/arklight.js`'s
  `window.ArkBackend`) with a working "Try Again" button
  (`ArkNativeBridge.retryBackend()`), instead of only being logged.

- **Test:**
  Rebuild and reinstall on-device; confirm the app no longer crashes
  when `launchNode()` hits the (still-expected, per this service's own
  class doc) "entry.js missing" branch, and that the failure screen
  with a working "Try Again" button appears instead.

- **Notes:**
  This does not fix backend startup itself -- `code-server`'s
  server-side `out/node/` bundle still needs its own vendoring pass
  (see `IdeBackendService.kt`'s class doc and
  `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 5b/node-pty) before the
  backend can actually reach `Ready`. This bug is specifically about
  the app surviving and communicating that failure instead of being
  killed by the OS.
  `assets/backend-failed.png`, used by the new failure screen, is a
  generated placeholder graphic (not final art) -- swap it for real
  artwork before shipping.

---

## Rules

1. Every discovered bug gets an entry under **Active Bugs**.
2. Do not remove a bug merely because a fix was written.
3. A bug is removable only when:
   - the fix is implemented,
   - the relevant test passes,
   - the original reproduction no longer fails,
   - and the fix does not introduce a regression.
4. Once all verification succeeds, remove the bug from this file.
5. Do not keep a separate "Fixed Bugs" section here. Git history, commits, PRs, or changelogs should provide the historical record.
6. If a supposedly fixed bug reappears, create a new entry with a new bug ID and reference the previous fix in `Notes`.
7. Keep entries focused on observable failures rather than vague concerns or speculative cleanup.
8. Tag every entry with **Stage** (`v1`/`v2`/`v3`) -- ARKware spans three runtimes, and a bug's runtime is part of diagnosing it, not incidental.

---

## Verification Standard

A bug may be removed only after:

```text
Bug reproduced
    ↓
Root cause identified
    ↓
Fix implemented
    ↓
Build/compile succeeds
    ↓
Regression test passes
    ↓
Original reproduction passes
    ↓
Fix confirmed working
    ↓
BUG REMOVED
````

---

## Bug Entry Template

```md
### BUG-XXXX: Short description
- **Status:** `UNFIXED`
- **Found:** YYYY-MM-DD
- **Stage:** `v1 | v2 | v3`
- **Location:** `path/to/file:line`
- **Severity:** `Critical | High | Medium | Low`

- **Description:**
  ...

- **Expected:**
  ...

- **Actual:**
  ...

- **Reproduction:**
  1. ...
  2. ...
  3. ...

- **Likely cause:**
  ...

- **Fix:**
  ...

- **Test:**
  ...

- **Notes:**
  ...
```
