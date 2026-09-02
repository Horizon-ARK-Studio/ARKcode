# ARKware

## A thin native shell for SPAs.

ARKware takes a single-page app and wraps it in exactly enough
native shell to make it look and feel like a real, installed app —
without rewriting it.

It looks like the app.

It behaves like an app.

No redesign.
No framework lock-in inside the SPA.
Just the SPA, with the native chrome a browser tab (or a heavier
runtime like Electron/Capacitor) doesn't give you for free.

---

## The idea

ARKtube proved the pattern once, specifically: wrap `m.youtube.com`
in a native Android shell, add real fullscreen, orientation lock,
and OS media-session integration, change nothing else.

ARKware is that pattern, generalized: point the shell at *any* SPA,
and give it only the native affordances the web layer structurally
can't reach itself.

```text
Any SPA
    +
the smallest native shell that fixes what the
web runtime can't (fullscreen, window chrome,
OS integration, media/session hooks)
    =
The SPA, installed.
```

---

## Platforms

| Stage | Platform | Shell |
|---|---|---|
| **v1** | Android | Native Kotlin, `WebView` + GoF patterns where they earn their place |
| **v2** | Desktop | [Neutralino](https://neutralino.js.org/) **window mode** — OS-native webview (WebView2 / WebKit / WebKitGTK) |
| **v3** | Desktop | Neutralino **chrome mode** — delegates to the system's installed Chrome/Chromium (`--app`), no bundled runtime, used only where window mode structurally can't do something the SPA needs |

iOS is explicitly out of scope — see
[`docs/Foundational/PROBLEM-STATEMENT.md`](docs/Foundational/PROBLEM-STATEMENT.md#non-goals)
for why.

---

## Status

🚧 **Stage 0 — docs only.** No code yet. See
[`docs/README.md`](docs/README.md) for the index and
[`docs/Foundational/ROADMAP.md`](docs/Foundational/ROADMAP.md) for
what each stage actually has to prove before the next one starts.

---

## License

[GNU General Public License v3.0](LICENSE) (GPL-3.0-or-later). ARKware
ships no code or assets belonging to any SPA it shells — it is a
generic native shell, not a redistribution of anything it points at.
