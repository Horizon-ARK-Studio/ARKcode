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

---

### BUG-0002: Backend never reaches Ready even with entry.js vendored -- libnode.so's shared-library dependencies aren't
- **Status:** `PARTIALLY FIXED (diagnosis + explicit check landed; the actual libraries are NOT vendored)`
- **Found:** 2026-09-03
- **Stage:** `v1 (Android)`
- **Location:** `android-project/app/src/main/java/com/arkware/ide/backend/IdeBackendService.kt:169` (`launchNode`)
- **Severity:** `Critical`

- **Description:**
  `libnode.so` (`jniLibs/arm64-v8a/libnode.so`) is a Termux-built
  binary. `readelf -d` on it shows it's dynamically linked against 8
  shared libraries beyond Android's own bionic libc/libm/libdl:
  `libz.so.1`, `libcares.so`, `libsqlite3.so`, `libcrypto.so.3`,
  `libssl.so.3`, `libicui18n.so.78`, `libicuuc.so.78`,
  `libc++_shared.so`. Its baked-in `DT_RUNPATH` points at
  `/data/data/com.termux/files/usr/lib`, which does not exist in this
  app's sandbox. None of these 8 libraries are vendored alongside
  `libnode.so` in `jniLibs/<abi>/` today -- only `libnode.so` itself is
  present.

  Verified independently of Android: fetching the real `code-server`
  release via `scripts/vendor-code-server.sh` +
  `scripts/vendor-code-server-server.sh` and running the resulting
  `out/node/entry.js` directly with a desktop Node binary starts and
  reports its bound port in well under a second and stays healthy --
  so once `libnode.so` can actually execute, `entry.js` itself is not
  the blocker.

- **Expected:**
  With `entry.js` vendored, the backend reaches `IdeBackendState.Ready`
  and the WebView navigates to it.

- **Actual:**
  `ProcessBuilder(command).start()` succeeds at the OS level (fork+exec
  itself doesn't fail), but the bionic dynamic linker inside the new
  process can't resolve the 8 missing `NEEDED` libraries and aborts the
  child almost immediately. Before this fix, that abort message only
  ever reached Logcat via `drainStderr()` -- `IdeBackendState.Failed`'s
  reason was just the generic "node process exited before ever
  reporting a bound port", giving no indication of the real cause.

- **Reproduction:**
  1. Vendor both `code-server` halves (`scripts/vendor-code-server.sh`
     and `scripts/vendor-code-server-server.sh`) so `entry.js` exists.
  2. Build and install on an arm64 device.
  3. Launch the app -- backend never reaches `Ready`.
  4. Before this fix: `adb logcat` shows a dynamic-linker "library ...
     not found" abort, but the app's own Failed-state UI/reason gives
     no hint of it.

- **Likely cause:**
  `jniLibs/arm64-v8a/` only ever vendored `libnode.so` itself (see
  `ed11de5`, "Vendor libnode.so (arm64-v8a) and fix LD_LIBRARY_PATH for
  exec"), not the shared libraries the Termux build linked it against.

- **Fix (landed):**
  `launchNode()` now checks for all 8 `REQUIRED_NATIVE_LIBS` under
  `applicationInfo.nativeLibraryDir` before ever attempting exec, and
  `fail()`s with their exact missing filenames if any are absent.
  `drainStderr()` now keeps a bounded tail of the child's stderr
  output; if the process exits without ever reporting a port, that
  tail is folded into the `Failed` reason so a real (not just
  predicted) dynamic-linker error would now be visible in the UI/logs
  instead of only Logcat.

- **Fix (NOT landed -- the actual blocker):**
  The 8 libraries themselves still need to be vendored into
  `jniLibs/arm64-v8a/` alongside `libnode.so` -- same Termux
  `nodejs-lts` build as `libnode.so` itself (plan section 5b). This is
  a real NDK/cross-compile task, not something fixable by editing
  Kotlin.

- **Test:**
  Once the libraries are vendored: rebuild, reinstall, launch, and
  confirm `IdeBackendState.Ready` is reached and the WebView navigates
  to the real backend. Until then: confirm the pre-flight check fires
  with the correct missing-file list (delete/rename one of the vendored
  libs and relaunch) rather than the process being exec'd and failing
  opaquely.

- **Notes:**
  Diagnosed by actually running both vendor scripts and `readelf -d`
  against the real `libnode.so` in this repo, and separately trial-
  running the real `entry.js` under a desktop Node to rule it out as a
  contributing cause.

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
