# Index for ARKware Documents

* [Foundational/PROBLEM-STATEMENT.md](Foundational/PROBLEM-STATEMENT.md) -- the design document: what ARKware is, why it's a thin generic shell instead of a rewrite or an Electron/Capacitor-style runtime, and why iOS is explicitly out of scope.
* [Foundational/ROADMAP.md](Foundational/ROADMAP.md) -- the v1/v2/v3 staging: what each stage has to prove working before the next one starts, and what's explicitly deferred until then.
* [Foundational/CODE-STYLE.md](Foundational/CODE-STYLE.md) -- how we write code, per platform: package-per-concern layout, when a GoF pattern earns its place on Android, the equivalent discipline for the Neutralino/JS shell, and the shared logging convention.
* [Foundational/SYSTEM-DESIGN-AGREEMENTS.md](Foundational/SYSTEM-DESIGN-AGREEMENTS.md) -- who's allowed to own what, between this shell's native layer and whatever runtime is actually rendering the SPA (`WebView`/Chromium on Android, the OS webview or system Chrome on desktop). Generalizes the same ownership question ARKtube had to answer for `WebView`, this time across three different runtimes instead of one.
* [bugs-caught/README.md](bugs-caught/README.md) -- active bug tracker. Bugs stay listed here until fixed, tested, and confirmed working.
* [../LICENSE](../LICENSE) -- ARKware is licensed under the GNU General Public License v3.0 (GPL-3.0-or-later). Every platform shell (Android now, the Neutralino desktop shells and their npm CLI once they land) ships under the same terms.

---

## Philosophy

Same philosophy as ARKtube's docs, because it's the same shape of
problem one level more general: **the codebase should be legible to
whoever opens it next, including a future version of whoever wrote
it.** A few things that follow from that, here specifically:

* **Explain the constraint, not just the code.** `PROBLEM-STATEMENT.md`
  exists to answer "why a shell, why these three runtimes, why not
  iOS" -- not "what does the code do," which the code itself already
  answers.
* **The pattern is proven once; it still has to be re-proven per
  runtime.** ARKtube already showed the Android half of this works.
  That's a reason to reuse its ownership model (see
  `SYSTEM-DESIGN-AGREEMENTS.md`), not a reason to assume Neutralino's
  window mode or chrome mode inherit the same guarantees automatically
  -- each runtime gets its own honest accounting of what it owns.
* **Don't reach for structure the problem hasn't asked for yet.** Same
  rule as ARKtube: a GoF pattern, a new package, a new abstraction is
  adopted because it's the accurate name for a constraint the code
  already ran into.
* **Stay small on purpose, per stage.** `ROADMAP.md` exists specifically
  so "wouldn't it be nice to also do X now" has somewhere to go that
  isn't the current stage's scope.
* **Bugs stay visible until they're actually gone.** Same
  fixed/tested/confirmed bar as ARKtube's `bugs-caught/`.
