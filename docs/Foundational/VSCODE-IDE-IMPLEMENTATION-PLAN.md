# ARKware → On-Device VS Code IDE: Implementation Plan

Status: **draft, Node/code-server sourcing intentionally open (§5)**

> **Progress note (this patch):** Phase 0 landed --
> `android-project/` now exists (previously this branch was docs-only,
> Stage 0), scaffolded via `arklight android scaffold` off ARKlight's
> `alpha` branch per §3, hand-edited into the package-per-concern shape
> `CODE-STYLE.md` calls for (`backend/`, `logging/`). Phase 1's
> placeholder backend + port-handoff plumbing also landed:
> `IdeBackendService` binds a loopback socket on `127.0.0.1:0`, `Ready`/
> `Failed`/`Starting` states flow to `MainActivity` over a bound-service
> `StateFlow`, and `MainActivity` navigates the `WebView` off the static
> placeholder page once the backend reports a real port. See
> `bugs-caught/` for one correction this surfaced (§2's claim that
> `MediaPlaybackService.kt` already exists in this repo to copy the
> shape from -- it doesn't yet; `IdeBackendService` was written from
> this document's own reasoning instead).
>
> The VS Code **workbench** itself (this document's actual subject --
> the static frontend under `lib/vscode/out/vs/` in any `code-server`
> release, `product.json` alongside it) is intentionally *not* checked
> into `android-project/`. See `scripts/vendor-code-server.sh` and its
> own header for why (~56MB even after dropping every server-side/
> arch-specific file, which is real weight to carry in git history
> forever versus fetching once at build time) and for the exact pinned
> version (`code-server` v4.135.0) and checksum this patch verified the
> script against. Run it once locally (`./scripts/vendor-code-server.sh`
> from `android-project/`) to populate
> `app/src/main/assets/code-server/workbench/` before a Gradle build
> that expects it to be present -- Phase 3 is what actually wires that
> directory up to be served, not this patch (see §7's phase table,
> unchanged).
>
> Phase 2 sourcing has one input now too:
> `android-project/vendor/termux-packages/nodejs-lts/` is
> termux-packages' own bionic-targeting Node build recipe (§5 option
> (b)), vendored verbatim for reference. See that directory's own
> `README.md` for exact provenance/licensing and, importantly, for
> everything it still takes to turn this recipe into an actual binary
> -- vendoring the recipe is sourcing only, not a build step; nothing
> in this patch invokes it.
>
> **Progress note (this patch):** `IdeBackendService` now execs for
> real per §4.2 (`ProcessBuilder` against
> `nativeLibraryDir/libnode.so` running `filesDir/code-server/out/node/entry.js`,
> stdout scanned for the bound port, `Process.destroy()`/
> `destroyForcibly()` wired into `onDestroy()` per §4.5) instead of the
> placeholder `ServerSocket` Phase 1 left behind -- but it still fails
> loudly into `IdeBackendState.Failed` on-device today, for two
> reasons neither this patch nor any CI step in this repo resolves:
> `libnode.so` has no built binary yet (§5(b)'s recipe is vendored,
> not run -- that build needs a real NDK + host LLVM toolchain and, per
> the recipe's own `build.sh` comment, takes on the order of hours per
> ABI), and `scripts/vendor-code-server.sh` only ever fetches
> `code-server`'s platform-independent *workbench* frontend, never its
> server-side `out/node/` bundle (deliberately -- see that script's own
> header on the linux-x64 `node-pty` addon being a separate, §4's
> Phase-4 problem). `app/build.gradle.kts` gained the
> `packaging { jniLibs { useLegacyPackaging = true } }` + `abiFilters`
> wiring §4.1 needs for whichever binary eventually lands there. §6's
> SAF workspace picker landed in full: `WorkspaceManager` (new
> `workspace/` package) owns persisted-`Uri` + newer-or-missing
> copy-in/copy-out against `filesDir/workspace/`, and `MainActivity`
> gained the `OpenDocumentTree()` launcher plus an always-available
> overlay button (the `NoActionBar` theme rules out a menu-bar entry
> point) and syncs in before every backend start / out on every
> `onStop()`.

This plan replaces ARKware's current `vscode` flavor -- which just
points the shell at the *remote* `vscode.dev` SPA, per
`android-project/README.md` -- with a self-contained, offline-capable
VS Code IDE: `code-server`'s bundled Code-OSS frontend + a real Node.js
backend, both running **on-device**, with the existing `WebView` shell
pointed at `localhost` instead of the internet. It also folds in
ARKlight (`alpha` branch)'s Android backend as the generator for the
outer Gradle project shell, since it already does that job better than
anything bespoke to ARKware would.

---

## 0. Two prior claims corrected before this plan starts

Both corrections are load-bearing, not cosmetic -- the plan below is
shaped around them.

1. **"Vendor Termux's compiled packages directly" does not work on
   modern Android**, for two independent reasons:
   - Termux's `node`/`git`/`openssh` binaries have `PT_INTERP` (the
     ELF dynamic linker path) hardcoded at compile time to
     `/data/data/com.termux/files/usr/...`. That path does not exist
     inside a *different* app's sandbox (different `applicationId`,
     different UID). Copying the binary doesn't help without also
     replicating Termux's exact prefix tree or `patchelf`-ing every
     binary to point at a linker you also ship.
   - Since Android 10 (API 29), an app's private storage is mounted
     `noexec` for anything extracted there post-install -- `chmod +x`
     on a runtime-extracted binary and then executing it is blocked by
     the mount flag and SELinux, independent of the linker-path issue
     above. The only directory `PackageManager` extracts with exec
     permission preserved is `app/src/main/jniLibs/<abi>/`, mapped to
     `ApplicationInfo.nativeLibraryDir` at install time. **Any vendored
     native binary in this plan ships as a renamed `.so` under
     `jniLibs/`, full stop** -- this is how Termux itself had to adapt
     its own installer, and it's not optional.
2. **`Rae-ARK/ARKlight`'s `main` branch has no Android output at all**
   (pure Python→HTML/CSS compiler). Its `alpha` branch does --
   `arklight android scaffold` templates a real Gradle/Kotlin project
   using `androidx.webkit.WebViewAssetLoader`. That's a static-asset
   loader (serves a build-time-baked file tree over a virtual
   `https://appassets.androidplatform.net` origin), not a live backend
   process. It's a good generator for the *outer shell*; it does not
   and cannot substitute for the Node/code-server backend a real VS
   Code workbench needs (extension host, language servers, integrated
   terminal, file-system access all require a running process, not
   baked files).

---

## 1. Target architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         ARKware Android app                      │
│                                                                    │
│  ┌────────────────────────┐        ┌────────────────────────────┐│
│  │ MainActivity / WebView  │        │ IdeBackendService            ││
│  │                         │        │ (foreground Service)         ││
│  │ loads                   │  HTTP  │                              ││
│  │ http://127.0.0.1:PORT ──┼───────►│ ProcessBuilder execs          ││
│  │ (loopback only, never   │        │ nativeLibraryDir/libnode.so   ││
│  │ 0.0.0.0 -- see §4)      │        │ running code-server's         ││
│  │                         │        │ out/node/entry.js             ││
│  └────────────────────────┘        └───────────────┬──────────────┘│
│                                                       │ spawns       │
│                                      ┌────────────────▼────────────┐│
│                                      │ extension host, integrated  ││
│                                      │ terminal (pty), LSPs, file  ││
│                                      │ watcher -- all real child   ││
│                                      │ processes of the Node proc  ││
│                                      └──────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

Two processes, same app, same UID: the `Activity`'s `WebView` never
executes anything itself, it's a thin HTTP client against a backend
this app also owns and starts. This is the same shape `code-server`
already uses on a desktop Linux box (Node backend + Code-OSS frontend
served over HTTP) -- nothing here is Android-specific except *how the
Node binary gets permission to execute* (§4) and *how it reaches
storage* (§6).

---

## 2. What ARKware already gives us for free

From `android-project/README.md` and the current source tree:

- **Config-driven flavor system** (`app/build.gradle.kts`
  `productFlavors`) is the right place to add an `ide` flavor
  alongside `youtube`/`vscode`/`template` -- except this flavor's
  `TARGET_URL` needs to become `http://127.0.0.1:PORT` (dynamic,
  resolved at runtime once the backend service reports its bound
  port) rather than a static per-flavor string, so `SpaConfig` needs a
  small extension: an optional "target URL is resolved at runtime by
  a local service" mode instead of always reading a fixed
  `BuildConfig` string.
- `ArkWebViewFactory` / `webview/bridge/` already establish the
  `@JavascriptInterface` bridge pattern -- reusable for anything the
  IDE frontend needs to ask the native layer for that a browser
  extension host normally can't get (see §6, storage access).
- `theme/`, `fullscreen/`, `media/` are irrelevant to an IDE target and
  should **not** be wired into the `ide` flavor -- `SpaConfig`'s
  per-flavor values already make this a no-op for flavors that don't
  need them; no shared code needs touching.
- `MediaPlaybackService.kt` is a working, tested example of *exactly*
  the pattern `IdeBackendService` needs (foreground `Service`,
  promoted to foreground only once real activity is confirmed, proper
  lifecycle) -- reuse its shape, not its content.
- The GitHub Actions workflow
  (`.github/workflows/android-build.yml`) already builds/smoke-tests/
  signs per the existing repo; extending it to also fetch/verify the
  vendored Node `.so` (§5) and code-server release before packaging is
  additive, not a rewrite.

## 3. What ARKlight (`alpha`) contributes, precisely scoped

`arklight android scaffold` is a **project-shell generator**, not a
runtime dependency. Its role here:

- Run it once, offline, at repo-scaffolding time (not at app runtime,
  not shipped inside the APK) against a placeholder/empty build
  directory, to generate the initial Gradle/Kotlin project skeleton --
  `AndroidManifest.xml`, `build.gradle.kts`, icon/splash resource
  wiring, the `WebViewAssetLoader` origin setup -- then **hand-edit
  the result into ARKware's existing flavor structure** rather than
  keeping it as a separate generated project. Treat its output as a
  one-time template pull, same way you'd copy a starter template out
  of a scaffolding tool and then own the copy.
- Its `WebViewAssetLoader` wiring is worth keeping for one specific,
  narrow use: any genuinely static asset this app ships (onboarding
  page, offline/error page, licenses page) can be served through the
  same virtual-origin mechanism instead of the older `file://` scheme,
  for the CORS/`fetch()` reasons noted above. The VS Code workbench
  itself is **not** served this way -- it comes from the live
  `code-server` process over real loopback HTTP, per §1.
- Everything backend/runtime in this plan (§4, §5, §6) has zero
  dependency on ARKlight. If the ARKlight-generated shell turns out to
  not be worth the hand-edit versus writing the flavor by hand, this
  plan doesn't change -- ARKlight is upstream-of, not load-bearing
  for, the IDE functionality itself.

## 4. `IdeBackendService`: getting a real Node process running

1. **Packaging.** `code-server`'s release tarball ships its own bundled
   Node runtime. Whatever Node binary is chosen (§5), rename it
   `libnode.so` and place per-ABI copies under
   `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`. Everything
   else `code-server` needs (its `out/` JS, `node_modules/` with any
   native `.node` addons) ships as regular asset files under
   `app/src/main/assets/code-server/`, extracted to
   `filesDir/code-server/` on first run (plain file copy, not
   execute -- `noexec` only blocks execution, not read/copy, so this
   part is unaffected by §0's constraint). Any *native addon* `.node`
   files inside `node_modules` hit the same `noexec` wall as the Node
   binary itself and need the same `jniLibs` treatment -- audit
   `code-server`'s dependency tree for these before packaging (common
   ones: `node-pty` for the integrated terminal, which is exactly the
   one you can't skip).
2. **Launch.** `IdeBackendService` (foreground `Service`, mirroring
   `MediaPlaybackService`'s promote-only-when-real shape) runs:
   ```
   ProcessBuilder(
       "$nativeLibraryDir/libnode.so",
       "$filesDir/code-server/out/node/entry.js",
       "--bind-addr", "127.0.0.1:0",   // :0 = OS picks a free port
       "--auth", "none",                // loopback-only; see below
       "--user-data-dir", "$filesDir/code-server/user-data",
   )
   ```
   `--bind-addr 127.0.0.1:0` matters twice over: binding `127.0.0.1`
   rather than `0.0.0.0` keeps the server unreachable from anything
   off-device (no other app, no LAN peer can connect even if the port
   were guessed), and port `0` avoids a hardcoded port colliding with
   another app or a previous instance that didn't clean up. Parse the
   actual bound port from the process's stdout (code-server logs it on
   startup) and hand it to `MainActivity` via a bound-service callback
   or a `LocalBroadcastManager` message -- this is what makes the
   flavor's target URL "resolved at runtime" per §2.
3. **`--auth none` is defensible specifically because of loopback
   binding**, not in general -- there's no network path to the server
   that doesn't already require code-execution-level access to this
   app's own sandbox. If you want defense in depth anyway (e.g.
   against a malicious co-installed app hitting `127.0.0.1` from its
   own process, which loopback binding does *not* prevent -- any app
   on the device can still open a socket to `127.0.0.1:PORT`), switch
   to `--auth password` with a random password generated at first run,
   stored in `EncryptedSharedPreferences`, and injected into the
   WebView's first request via a query param or the
   `@JavascriptInterface` bridge rather than typed by the user.
4. **Cleartext traffic config.** `targetSdkVersion 28+` blocks
   cleartext (`http://`) traffic by default with **no automatic
   exception for loopback**. Add:
   ```xml
   <!-- res/xml/network_security_config.xml -->
   <network-security-config>
       <domain-config cleartextTrafficPermitted="true">
           <domain includeSubdomains="false">127.0.0.1</domain>
       </domain-config>
   </network-security-config>
   ```
   and reference it from `AndroidManifest.xml`'s
   `android:networkSecurityConfig`. Skipping this produces a silent
   `net::ERR_CLEARTEXT_NOT_PERMITTED` in the WebView with no obvious
   cause.
5. **Process lifecycle.** Kill `code-server`'s process explicitly in
   `IdeBackendService.onDestroy()` (`Process.destroy()`, escalate to
   `destroyForcibly()` on timeout) -- Android does not guarantee a
   child process dies with its parent `Service`, and an orphaned Node
   process holding a `127.0.0.1` port across app restarts is exactly
   the kind of bug that's invisible until a second launch mysteriously
   can't bind.

## 5. Node / code-server sourcing -- open, deferred per your call

Two live options, neither started yet:

- **(a) Repackage an official `node-vX-linux-arm64`/`-armv7l` release
  tarball**, targeting this app's own `filesDir` prefix from the
  start (no Termux prefix assumption to fight). Simpler, but you own
  verifying the prebuilt binary's dynamic library dependencies
  (`ldd`-equivalent check) actually resolve against a bare Android
  userspace -- stock Node release builds link against glibc, and
  Android's `bionic` libc is not glibc-ABI-compatible. This is very
  likely the actual blocker with this path, worth resolving before
  committing to it: a straight glibc-linked binary will not run on
  Android at all regardless of the `jniLibs` packaging trick, since
  that trick only solves *where the file lives and whether it's
  executable*, not *what libc it was linked against*. You'd need
  either a `musl`- or `bionic`-targeted Node build (the NDK can
  cross-compile Node against `bionic` directly, distinct from
  Termux's own patched build), or a static/`musl` build via something
  like `node-static-binaries`.
  - **Correction to my own §5(a) framing above once verified**: the
    "Termux prefix" issue (§0.1) is a *path* problem; glibc-vs-bionic
    is a *linking* problem. Fixing one doesn't fix the other -- a
    from-scratch build needs both addressed, not just the path one.
- **(b) Build Node against Termux's `bionic`-targeted toolchain**
  (`termux-packages`' build scripts, run once, offline, to produce the
  binary -- not vendoring Termux's *installed* userland, which is
  what §0.1 rules out). This gets you a binary already proven to run
  on `bionic`, at the cost of adopting Termux's build infrastructure
  as a one-time dependency of your own CI, not of the shipped app.

Recommendation once you're ready to pick: **(b)** is lower-risk given
it sidesteps the glibc/bionic question entirely by construction, at
the cost of a heavier one-time CI setup; **(a)** is worth it only if
you find (or build) a `bionic`-targeted Node release, since a
glibc-linked one is a dead end regardless of `jniLibs` packaging.

## 6. Filesystem: workspace storage the extension host can actually reach

`code-server`'s Node process is a normal Linux process running as this
app's own UID -- it can read/write anything inside `filesDir`/
`cacheDir` with zero extra permission, no different from any other
file this app owns. The only reason to touch Android's storage
permission model at all is if workspaces should live **outside** the
app's private sandbox (e.g. a folder the user picks under
`/storage/emulated/0/...` via the system file picker, so files persist
uninstall-to-uninstall and are visible to other apps).

- **Do not** request `MANAGE_EXTERNAL_STORAGE` for this -- it's a
  Play Store policy red flag (restricted permission, requires a
  declaration form and is rejected for most non-file-manager apps) and
  is broader than what's needed.
- **Do** use Storage Access Framework:
  `ActivityResultContracts.OpenDocumentTree()` to let the user pick a
  workspace folder once, persist the returned `Uri` permission via
  `ContentResolver.takePersistableUriPermission(...)`, and either (i)
  have the Node process work against a `DocumentFile`-backed FUSE-ish
  bridge (nontrivial, avoid unless required), or (ii) the pragmatic
  default: copy the chosen folder's contents into `filesDir/workspace/`
  on open and copy changes back out on save/close/periodic sync,
  keeping the Node process's actual working directory entirely inside
  the app sandbox where no `noexec`/permission complications apply.
  (ii) is the ARKware-idiomatic choice -- same "don't reach for
  structure the problem hasn't asked for yet" rule the repo's docs
  already state in `docs/README.md`, and avoids a live external-storage
  dependency this plan doesn't otherwise need.

## 7. Phased delivery order

Sequenced so each phase is independently demoable, no phase blocks on
a §5 decision until Phase 2:

| Phase | Deliverable | Depends on |
|---|---|---|
| 0 | `ide` Gradle product flavor scaffolded (copy ARKlight-`alpha`'s generated shell in, hand-edit into ARKware's existing flavor structure per §3); `SpaConfig` extended for runtime-resolved target URL | none |
| 1 | `IdeBackendService` skeleton that starts/stops a **placeholder** process (e.g. `python3 -m http.server` equivalent, or even a static "hello" HTTP server) bound to `127.0.0.1:0`, WebView loads it, port-handoff plumbing proven end-to-end | Phase 0 |
| 2 | Real Node binary sourced per §5's eventual decision, packaged per §4.1 | Phase 1 |
| 3 | `code-server` bundle wired up, `IdeBackendService` launches it for real per §4.2-4.5, workbench renders in WebView | Phase 2 |
| 4 | `node-pty`/integrated terminal confirmed working (the concrete test of whether native-addon `jniLibs` packaging in §4.1 actually holds up) | Phase 3 |
| 5 | Workspace storage: SAF folder picker + sandbox-copy sync per §6 | Phase 3 (parallel with 4) |
| 6 | CI: extend `android-build.yml` to fetch/verify the vendored Node `.so` + code-server release before assembling, so a stale/missing vendored binary fails CI loudly instead of shipping silently broken | Phase 2 |

Phase 4 is the real risk gate: if `node-pty`'s native addon doesn't
build/run cleanly under whichever Node flavor §5 lands on, that's
discovered here, before further investment in Phases 5-6.
