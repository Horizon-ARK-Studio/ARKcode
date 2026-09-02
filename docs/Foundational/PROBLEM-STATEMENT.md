# ARKware

## Design Document

**Status:** Experimental
**Target:** Android (v1), Desktop via Neutralino (v2/v3)
**Shells:** Native Kotlin `WebView` (Android); Neutralino window mode, OS-native webview (desktop v2); Neutralino chrome mode, system Chrome/Chromium (desktop v3)
**Input:** Any SPA -- a URL, or a locally-served build, that the shell points a webview/runtime at
**Primary goal:** Make an arbitrary SPA behave like a proper installed native app -- without redesigning it, and without paying an Electron/Capacitor-sized runtime tax to get there.

---

## 1. Objective

ARKware generalizes the pattern ARKtube proved on a single target
(`m.youtube.com`, Android only) into a reusable shell: point it at a
SPA, and it should

* look and feel like that SPA's own responsive layout -- no visual
  changes made on ARKware's behalf
* retain the SPA's own navigation, state, and behavior exactly as it
  runs in a normal browser tab
* run comfortably on modest hardware, not just recent flagships/dev
  machines -- see Section 2 of ARKtube's own `PROBLEM-STATEMENT.md`
  for why this matters and stays true here
* add native OS integration only where the *web* experience
  structurally can't reach it (fullscreen compositing, OS-level
  media/session hooks, window chrome, tray/dock integration,
  install/update mechanics)
* never require the SPA itself to know it's running inside ARKware,
  unless the SPA specifically wants to use an ARKware-provided bridge

The guiding principle carried over unchanged from ARKtube:

> **Do not redesign the app. Change the execution model around it.**

What's different from ARKtube is scope: ARKtube committed to one
SPA and one runtime. ARKware has to keep that same discipline while
supporting an arbitrary SPA across three different underlying
runtimes (Android `WebView`, desktop OS-native webview, desktop
system Chrome). Section 4 covers why that isn't three unrelated
shells that happen to share a name.

---

## 2. Why Not Electron / Capacitor / a Full Rewrite

This is worth answering directly, because it's the first question
anyone familiar with existing app-shell tooling will ask.

* **Electron bundles an entire Chromium + Node runtime per app.**
  That's a deliberate, reasonable tradeoff for teams that want total
  control and don't mind the footprint. It's the opposite of what
  ARKware is for: every megabyte and every resident process Electron
  ships is exactly the weight ARKtube's low-end-device argument (see
  ARKtube `PROBLEM-STATEMENT.md` Section 2) was built to avoid.
  Neutralino's own pitch -- use whatever webview the OS already has,
  ship a small binary, no bundled browser engine -- is the desktop
  half of the same bet ARKtube already made on Android.
* **Capacitor is a dependency this project deliberately isn't taking
  on.** Not because it's bad tooling -- because ARKware's whole reason
  to exist is owning the shell/runtime boundary directly (see Section
  4) rather than sitting on top of someone else's abstraction of it.
  A dependency here means ARKware's actual hard problems -- who owns
  fullscreen, who owns the media session, who owns window chrome --
  become Capacitor's problems to have already solved correctly for
  every one of its supported runtimes, which is exactly the kind of
  claim Section 4 says can't be taken on faith.
* **A full native rewrite of the SPA** throws away the actual
  argument for this whole approach: the SPA is already a complete,
  maintained product. ARKware's job is to stop competing with it and
  start wrapping it.

---

## 3. Non-Goals

This project is not:

* a new frontend framework, or a redesign tool for the SPAs it shells
* a general-purpose app framework competing with Electron/Tauri/
  Capacitor on feature completeness
* an offline-first or asset-bundling tool -- v1/v2/v3 all assume the
  SPA is reachable the way it already is (a URL, or however it's
  already served); bundling/offline is a plausible future addition,
  not a current goal
* a claim that every SPA is a good fit -- some genuinely need a real
  native rewrite, and ARKware doesn't try to be the answer for those
* **an iOS project, at all, for the foreseeable future.** Not "not yet"
  in the sense of "next on the roadmap" -- genuinely out of scope. The
  reasons are concrete, not aesthetic:
  * iOS has no equivalent of "use whichever engine the platform already
    exposes freely." Every browser engine on iOS, including anything
    calling itself Chrome, is required to be a `WKWebView` skin on top
    of Apple's own WebKit -- there's no system Chromium to delegate to
    the way desktop chrome mode can.
  * `WKWebView`'s own constraints (process model, JS bridge
    limitations, fullscreen/media-session APIs that don't map cleanly
    onto the same primitives ARKtube already had to fight for on
    Android `WebView`) are a materially different problem, not a
    smaller version of the Android one -- the ownership questions in
    Section 4/`SYSTEM-DESIGN-AGREEMENTS.md` would need re-deriving
    from scratch against WebKit's specific behavior, not ported.
  * That's real, dedicated-attention work, and ARKware v1-v3 don't
    have room for it without diluting the thing that made ARKtube's
    Android build actually work: full attention on one runtime's
    specific failure modes at a time (see ARKtube's own bugs-caught
    history for what "not full attention" costs). If iOS ever
    happens, it happens as its own fully-scoped stage, not a v4
    bullet point.

---

## 4. High-Level Architecture

```text
                         +------------------------+
                         |     The SPA (unowned)   |
                         |  UI, state, routing,    |
                         |  business logic, auth   |
                         +------------------------+
                                    |
                 points at / is loaded by
                                    |
      +-----------------+----------+----------+-----------------+
      |                 |                     |                 |
  Android v1       Desktop v2            Desktop v3          (iOS: N/A)
  native Kotlin    Neutralino            Neutralino
  WebView shell    window mode           chrome mode
  (own runtime:    (runtime: OS-native   (runtime: system
   Chromium        webview -- WebView2/  Chrome/Chromium,
   WebView)         WebKit/WebKitGTK)     launched --app)
```

Every stage owns the same two things, against a different runtime:

* **application lifecycle** -- install, launch, process/window
  management, update mechanics
* **exactly the native affordances the runtime's own web layer can't
  reach itself** -- see Section 5

Every stage explicitly does *not* own:

* the SPA's UI, layout, state, routing, or business logic
* anything the underlying runtime (Chromium, the OS webview, system
  Chrome) already manages correctly on its own

This split -- shell owns the app-shaped things, the runtime's web
layer owns everything web-shaped -- is identical to ARKtube's
Section 4 split between `WebView` and YouTube. What's new here is
that ARKware has to keep re-deriving *which specific things a given
runtime already owns*, because that answer is runtime-specific.
WebView2 doesn't expose the same surface as WebKitGTK; a `WebView`
custom-view fullscreen callback doesn't exist for system Chrome
launched via `--app`. See `SYSTEM-DESIGN-AGREEMENTS.md` for how each
runtime's ownership boundary actually gets answered, not assumed.

---

## 5. What the Shell Adds, and Why Each Runtime Needs It Answered Separately

The native affordances ARKtube had to add on Android -- real
fullscreen compositing, orientation lock, OS media-session
integration, status/nav bar theming, screen-wake during playback --
were all things `WebView`'s web layer structurally could not do
itself. That's Section 6 of ARKtube's `PROBLEM-STATEMENT.md`, and
the reasoning generalizes directly: **a shell only adds a native
affordance where the runtime's own web layer has a structural gap,
never because native felt more convenient to write.**

What does *not* generalize automatically is the specific list of
gaps, because each runtime's web layer has a different one:

* **Android `WebView`:** fullscreen video is handed off to a native
  `SurfaceView` outside the DOM entirely (ARKtube
  `PROBLEM-STATEMENT.md` Section 6) -- that gap is proven and
  documented.
* **Neutralino window mode (desktop v2):** the OS-native webview
  (WebView2 on Windows, WebKit on macOS, WebKitGTK on Linux) is three
  different engines with three different gap profiles -- window
  chrome, tray/dock integration, and OS-level media-session hooks are
  the known candidates, but which specific behaviors each engine's web
  layer can't reach itself has to be verified per-OS, not assumed from
  the Android case or from each other.
* **Neutralino chrome mode (desktop v3):** launching system Chrome via
  `--app` is a different ownership situation again -- ARKware doesn't
  control the browser process the way it controls an embedded webview,
  so some things v2 might own natively (window chrome behavior, for
  instance) may instead be whatever `--app` mode already gives for
  free, or may not be reachable at all. v3 exists specifically for
  SPA features that need something an OS-native webview's web layer
  can't do but an installed, fully-featured Chrome's web layer can --
  it is not a drop-in upgrade path from v2, and it should only be
  reached for when v2 has a proven, specific gap.

---

## 6. Success Criteria (Per Stage)

Deferred to `ROADMAP.md`, which owns the per-stage breakdown so this
document stays about *why* ARKware is shaped this way rather than
*what's currently shipped*.

---

## 7. Guiding Principle

> A SPA that already works well in a browser tab shouldn't need a
> full app-framework rewrite to also work well as an installed app.
> ARKware's job is to close that specific gap, runtime by runtime,
> proven one at a time -- not to become the next heavy thing sitting
> on top of the SPA it's supposed to be getting out of the way of.
