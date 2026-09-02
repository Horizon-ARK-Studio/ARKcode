# ARKware -- Roadmap

**Status:** Living document
**Scope:** whole project

`PROBLEM-STATEMENT.md` explains why ARKware is shaped the way it is.
This document says what has to be true, concretely, before a given
stage counts as done -- and just as importantly, what's deliberately
*not* attempted until then. Consistent with both projects' docs
philosophy: a stage that quietly grows scope mid-way is exactly the
kind of undocumented decision that looks like an accident later.

---

## v1 -- Android, native Kotlin shell

**Goal:** get the Android shell working with no outstanding issues,
generalized from ARKtube's proven pattern rather than YouTube-specific.

**In scope:**
* native Kotlin `WebView` shell, config-driven target SPA (not
  hardcoded to one site)
* everything ARKtube's Android build already proved: persistent
  `WebView` fullscreen handling, orientation lock to content shape,
  OS media-session integration, status/nav bar theming, rotation
  reflow -- reimplemented generically, not copy-pasted with the
  YouTube specifics left in
* GoF patterns where `CODE-STYLE.md` says one earns its place -- not
  by default

**Explicitly deferred:**
* anything desktop
* any SPA-provided native bridge/API beyond what ARKtube already
  needed (media state, title/artwork) -- a generic bridge surface is
  a v2+ conversation once there's a second runtime to design it
  against

**Done when (mirrors ARKtube `PROBLEM-STATEMENT.md` Section 12, generalized):**
* the shell installs, launches, and reaches a usable state for an
  arbitrary configured SPA noticeably faster than a heavy native
  rewrite would
* session/login state persists across restarts (whatever the SPA
  itself already persists via cookies/localStorage)
* fullscreen (where the SPA has a fullscreen affordance) reliably
  enters and stays entered -- no blink-and-revert
* rotation during fullscreen locks to content shape without breaking
  layout
* OS-level media controls correctly reflect and control real
  playback state, for SPAs that expose a `<video>`/`<audio>` element
* no outstanding entries in `bugs-caught/` for the Android shell

---

## v2 -- Desktop, Neutralino window mode

**Goal:** desktop capability via Neutralino, using its default window
mode -- the OS-native webview (WebView2 / WebKit / WebKitGTK) -- with
no dependency on system Chrome being installed.

**In scope:**
* Neutralino-based shell, same config-driven target-SPA model as v1
* window chrome, tray/dock integration, install/update mechanics --
  whatever a desktop user expects an installed app to have that a
  browser tab doesn't
* per-OS verification of which native affordances are actually
  missing from each OS-native webview's web layer (see
  `PROBLEM-STATEMENT.md` Section 5) -- this has to be checked per
  engine, not assumed from Android or from one desktop OS to another

**Explicitly deferred:**
* chrome mode (v3) -- v2 should exhaust what window mode can do
  before reaching for it
* any feature whose only known implementation path requires system
  Chrome specifically -- that's the definition of a v3 candidate, not
  a v2 workaround

**Done when:**
* the shell installs and runs on Windows, macOS, and Linux via
  Neutralino window mode
* session/login state persists across restarts on all three
* window chrome (resize, minimize/maximize, close, tray if
  applicable) behaves like a native app, not a browser window
* any native affordance gap found per-OS is documented (which OS,
  which webview engine, what's missing) before being worked around --
  not patched blind
* no outstanding entries in `bugs-caught/` for the desktop window-mode
  shell, on any of the three OSes

---

## v3 -- Desktop, Neutralino chrome mode

**Goal:** cover SPA features that window mode's OS-native webview
genuinely cannot support, by delegating to the system's already-
installed Chrome/Chromium (launched via `--app`) instead of an
embedded webview -- no bundled runtime, no bundled browser engine.

**In scope:**
* chrome-mode shell path, reached only for specific, documented
  feature gaps carried over unresolved from v2
* explicit detection/handling for the case where the system has no
  Chrome/Chromium installed -- what the shell does then has to be a
  deliberate decision, not silent failure

**Explicitly deferred / non-goal:**
* bundling Chromium -- chrome mode is specifically "use what's already
  on the system," not "ship our own copy"; if that stops being viable
  (e.g. install prevalence too low to rely on), that's a reason to
  revisit the approach, not to quietly start bundling
* using chrome mode as the *default* desktop path -- it stays a
  targeted escalation from v2, not a replacement for it

**Done when:**
* the shell can launch and control system Chrome in `--app` mode
  reliably across the same three OSes
* the specific v2 feature gaps that motivated v3 are confirmed fixed
  under chrome mode
* absence of a system Chrome/Chromium install is handled explicitly
  (documented fallback or clear failure, not silent breakage)
* no outstanding entries in `bugs-caught/` for the chrome-mode shell

---

## Non-goal, permanently: iOS

Not a future v4. See `PROBLEM-STATEMENT.md` Section 3 for why this
is a scope boundary, not a backlog item.
