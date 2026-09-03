# ARKware -- System Design Agreements

**Status:** Living document
**Scope:** whole project (architectural, not file-specific)

`CODE-STYLE.md` explains how code should be shaped once you know
what it owns. This document is one level up: **who is allowed to own
what**, between ARKware's native/shell layer and whatever runtime is
actually rendering the SPA.

ARKtube answered this question once, for one runtime
(`WebView`/Chromium on Android), and it was the recurring root cause
behind that project's worst bugs. ARKware inherits the same
question, but now has to answer it three times, against three
runtimes with different internals:

| Stage | Runtime | Same question, different internals |
|---|---|---|
| v1 | Android `WebView` (Chromium) | Answered by ARKtube already -- see below |
| v2 | OS-native webview (WebView2 / WebKit / WebKitGTK) via Neutralino window mode | Not yet answered -- three different engines, three separate answers needed |
| v3 | System Chrome/Chromium via Neutralino chrome mode (`--app`) | Not yet answered -- a launched external process, not an embedded runtime; the ownership shape itself is different, not just the specifics |

---

## The agreement (carried over from ARKtube, unchanged)

> **The runtime rendering the SPA is not a passive surface this shell
> controls. It is an independent system with its own opinions about
> media, layout, focus, and (for v3) its own process lifecycle. Every
> native subsystem in this shell must either (a) defer entirely to
> what the runtime already owns, or (b) own something the runtime
> provably does not touch -- never (c) hold a second, competing claim
> over the same resource.**

ARKtube's own framing of why this is a *system design* bug class, not
just "bugs" (two well-behaved systems both claiming the same
resource, each reacting to the other's actions as external reality)
applies verbatim here. See ARKtube's `SYSTEM-DESIGN-AGREEMENTS.md`
for the original BUG-0001/BUG-0004 case studies -- they're the
concrete evidence for why this rule exists at all, and they're
Android-`WebView`-specific evidence that a v2/v3 contributor should
still read before assuming their own runtime is somehow exempt.

---

## Why this doesn't port for free to v2/v3

It would be a mistake to read ARKtube's `SYSTEM-DESIGN-AGREEMENTS.md`
and assume its *conclusions* (e.g. "never hold a native
`AudioFocusRequest`, WebView already owns it") transfer to Neutralino
window/chrome mode. What transfers is the *method* -- the ownership
test below -- not the specific answers, because:

* **WebView2, WebKit, and WebKitGTK are three different engines with
  three different internal ownership models.** Whether a given engine
  already owns, say, OS media-session integration the way Chromium's
  `WebView` does is a per-engine empirical question, not something
  ARKtube's Android findings can answer by analogy.
* **Chrome mode (v3) isn't an embedded runtime at all.** It's a
  separate OS process this shell launches and has much looser control
  over. Some resources v2 might contest with an embedded webview (window
  chrome behavior, for instance) may not be a shared-ownership question
  in chrome mode at all -- they may simply belong entirely to the
  launched Chrome process, with the shell owning nothing there beyond
  the initial launch. That's a different *shape* of ownership question,
  not a stricter or looser version of v1/v2's.

---

## Applying it: the ownership test (same three questions, reapplied per runtime)

Before adding any shell code that touches a resource the active
runtime might also touch (window/fullscreen state, media/audio focus,
layout or inset handling, orientation, theming, notifications,
process/window lifecycle):

1. **Does the active runtime already manage this resource for
   whatever's on the page?** If yes, the shell does not also request
   or hold it. Mirror the runtime's resulting *state* where the OS
   needs to see it, never issue a second, independent claim on the
   resource itself.
2. **If the shell must act, is the action idempotent / re-run-safe, or
   does it only work correctly exactly once per real state
   transition?** Guard on the edge, not the poll -- same instinct
   ARKtube had to learn the hard way on both its worst bugs.
3. **Can the shell's action be observed by the runtime as an
   interruption it then "corrects," which the shell would in turn
   react to -- ad infinitum?** If steps 1 and 2 don't already rule
   this out, that loop is the bug, before a line of runtime-specific
   code is written.

This test is the actual reusable artifact from ARKtube's experience.
The per-runtime answers it produces belong in each stage's own notes
as they're discovered (v1 can cite ARKtube's existing findings
directly; v2's per-engine findings and v3's process-boundary findings
still need to be run through this test from scratch once there's real
code to test it against).

---

## Non-goals

Same as ARKtube's: this is not "never touch anything the runtime
touches." Window chrome, native fullscreen where applicable, and OS
media/session hooks all require the shell to act on resources the
runtime has *some* stake in -- that's the entire premise of
`PROBLEM-STATEMENT.md`. The agreement is narrower and non-negotiable:
when the shell acts, it must know *specifically* what the active
runtime already does with that resource, and either stay out of its
way or take over cleanly -- never leave both sides holding a claim on
the same thing at once.
