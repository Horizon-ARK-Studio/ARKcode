# ARKware -- How We Write Code

**Status:** Living document
**Scope:** `android-project/` (Kotlin) and `desktop-project/` (Neutralino/JS)

Same instinct as ARKtube's `CODE-STYLE.md`, carried over deliberately:
this isn't a brace-placement guide. It's the structural decisions
this codebase should keep making on purpose, so a change follows the
same shape instead of drifting toward one file that does everything
-- which is precisely the failure ARKtube's own `MainActivity.kt`
had to be refactored out of before its docs even existed.

---

## 1. One File, One Reason to Change

Unchanged from ARKtube: **a class/module exists because one thing can
change independently of everything else**, not because it was
convenient to keep adding to the file that was already open.

For the Android shell (v1), this means following ARKtube's own
package split directly -- `fullscreen/`, `webview/` (+ `bridge/`),
`media/`, `theme/`, `layout/`, `prefs/`, `logging/` -- generalized so
none of it hardcodes YouTube specifics. If a package's contents start
reading like "the YouTube version of X," that's the signal it still
has ARKtube-specific assumptions baked in that need to become
config-driven instead.

For the desktop shell (v2/v3), the same discipline applies even
though JS doesn't enforce it structurally the way Kotlin classes do.
A concern gets its own module for the same reason a concern gets its
own Kotlin package:

```
src/
├── main.js                 -- entry point + wiring only
├── shell/                   -- window chrome, tray/dock, lifecycle
├── webview-bridge/           -- SPA <-> native bridge (mirrors webview/bridge/)
├── media/                     -- OS media-session integration, if applicable
├── mode/
│   ├── window-mode.js        -- v2: OS-native webview specifics
│   └── chrome-mode.js        -- v3: system Chrome/--app specifics
├── config/                    -- target-SPA configuration
└── logging/                   -- shared logging convention, see Section 3
```

`mode/window-mode.js` and `mode/chrome-mode.js` living side by side,
each owning only its own runtime's specifics, is the desktop
equivalent of never letting `MainActivity.kt` know about both
fullscreen math and media-session binding in the same file. Shared
logic between the two modes belongs in `shell/`, not duplicated into
both, and not left in whichever of the two files was written first.

---

## 2. Reach for a Pattern When It Names a Real Constraint, Not by Default

Same rule as ARKtube: a GoF pattern on the Kotlin side, or an
equivalent structural device on the JS side, earns its place because
it's the accurate name for a constraint the code already hit -- never
because "that's how you'd structure this in general."

Two constraints already known to apply, carried directly from
ARKtube's own reasoning:

* **A single global failure log, reachable without threading a
  reference through every constructor/module.** ARKtube used a
  Kotlin `object` Singleton for `ArkLogger` for exactly this reason.
  The desktop equivalent is a single shared logging module, imported
  wherever needed -- same justification, no ceremony beyond what the
  constraint actually calls for.
* **Runtime-specific behavior behind a shared interface.** v2's
  window mode and v3's chrome mode need to expose the same shell-facing
  operations (show/hide window, enter/exit fullscreen where
  applicable, etc.) through genuinely different mechanics underneath.
  That's a Strategy-shaped constraint on the desktop side, the same
  way ARKtube reached for a named pattern only once a real
  one-interface/many-implementations situation existed -- not before.

Any other pattern gets added the same way: identify the constraint
first, name it after, not before.

---

## 3. Logging Convention

ARKtube's try/catch/finally + `ArkLogger` convention exists because a
WebView JS bridge or background service can fail silently in ways
that never surface as a normal crash. The same risk exists here,
across more runtimes:

* Android v1 reuses `ArkLogger`'s convention directly.
* Desktop v2/v3 needs an equivalent for its own two most likely
  silent-failure points: the SPA-bridge JS boundary, and mode
  switching/launch failures (a missing system Chrome install in
  chrome mode being the clearest example -- see `ROADMAP.md`'s v3
  "done when" criteria).

The specific logging implementation for desktop isn't decided yet --
that's a v2 decision once there's real bridge code to log around, not
a Stage 0 one.

---

## 4. This Document Grows With the Code, Not Ahead of It

Sections 1-3 cover what's already a known, real constraint (from
ARKtube's proven experience, or from the shape v2/v3 already imply).
Anything else -- test conventions, build tooling specifics, a
concrete first cut at the desktop bridge's package layout -- gets
added here once v1/v2 code exists to derive it from, not speculated
into this document ahead of time. Consistent with `docs/README.md`'s
own philosophy: don't reach for structure the problem hasn't asked
for yet.
