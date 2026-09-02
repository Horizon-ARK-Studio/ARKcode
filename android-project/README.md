# ARKware — Android shell (v1)

Native Kotlin `WebView` shell for the pattern described in the
[repo root README](../README.md) and
[`docs/Foundational/PROBLEM-STATEMENT.md`](../docs/Foundational/PROBLEM-STATEMENT.md):
**don't redesign the target SPA, change the execution model around
it.** This is a normal Android Studio / Gradle project — open it in
Android Studio, or build it from the command line once you have a
JDK:

```
./gradlew assembleYoutubeDebug
```

(or `assembleTemplateDebug`, or any other flavor added per below —
see "Adding a new SPA").

## Config-driven, not hardcoded to one SPA

Every collaborator under `com.horizonarkstudio.arkware` is generic —
none of it hardcodes a URL, a display name, or a DOM selector
belonging to any specific SPA. The one thing that *is* per-SPA is a
Gradle product flavor in `app/build.gradle.kts`, which supplies four
values:

| Value | Read by | Purpose |
|---|---|---|
| `TARGET_URL` | `config.SpaConfig.targetUrl` | the live URL the WebView loads |
| `SPA_DISPLAY_NAME` | `config.SpaConfig.displayName` | shown in the media notification's subtitle/artist field |
| `NAG_HIDE_SELECTORS` | `config.SpaConfig.nagHideSelectors` | comma-separated CSS selectors for an "open our app" nag banner, if the SPA has one |
| `NAG_HIDE_TEXT_MATCHES` | `config.SpaConfig.nagHideTextMatches` | comma-separated button text to match for the same purpose |

Each flavor also sets its own `applicationIdSuffix`, so every SPA this
shell is built for gets its own installable package:
**`com.horizonarkstudio.arkware.<spa-name>`**. Flavors in this
scaffold:

- **`youtube`** — the case ARKtube originally proved on its own,
  single-purpose Android build, reproduced here as just another
  flavor (`com.horizonarkstudio.arkware.youtube`, pointed at
  `m.youtube.com`). Its nag-hide values are left empty on purpose —
  real selectors have to be read off YouTube's actual markup on a
  device, not guessed here.
- **`vscode`** — points at `vscode.dev`
  (`com.horizonarkstudio.arkware.vscode`), the browser-based VS Code
  workbench. No install nag on that site, so nag-hide values are left
  empty.
- **`template`** — a neutral placeholder (`example.com`) to copy when
  scaffolding a new SPA; also what CI/a bare `./gradlew assembleDebug`
  falls back to if no flavor is specified.

### Adding a new SPA

Copy the `template` block in `app/build.gradle.kts`'s
`productFlavors`, rename it, and fill in the four values above. That's
the entire per-SPA surface area — nothing in `SpaConfig.kt`,
`ArkScripts.kt`, or any other shell code should ever need touching for
a new target.

## What's here

`MainActivity` is a thin Activity-lifecycle shell; the actual behavior
lives in single-responsibility collaborators under
`com.horizonarkstudio.arkware` (see each package's own class docs for
the full *why*):

- `config/` — `SpaConfig`, the single place that reads the
  per-flavor `BuildConfig` values described above.
- `fullscreen/` — everything about fullscreen video:
  `FullscreenVideoController` (facade), `SurfaceViewZOrderNeutralizer`
  (lets the native stretch-to-fill button paint/receive touches above
  Chromium's hardware-composited fullscreen `SurfaceView`),
  `ZoomCropStrategy`/`LetterboxZoomCropStrategy` (crops fullscreen
  video to fill the screen instead of a letterboxed default, by
  comparing the video's own intrinsic pixel size to its container and
  scaling the native customView), and `StretchToggleButtonFactory` (a
  manual override button for content the automatic crop doesn't
  catch).
- `webview/` — `ArkWebViewFactory` (builds the WebView + its
  settings) and `webview/bridge/` (the `@JavascriptInterface` bridges
  JS uses to report theme, fullscreen video size, and playback state
  back to native code); `ArkScripts` holds every script injected into
  the page, generic except for the two per-SPA parameters
  (`SpaConfig`) it's handed at call time.
- `theme/` — syncs the status/nav bar color to whichever theme the
  SPA itself is rendering (its own light/dark toggle, not the phone's
  system theme).
- `layout/LayoutReflowHelper` — forces the SPA's off-screen content to
  re-measure after a rotation.
- `media/` — `MediaSessionCoordinator` (Activity-side binding/JS
  transport dispatch) and `MediaNotificationFactory` (the
  `MediaStyle` notification itself).
- `prefs/` — persisted stretch-to-fill toggle state.
- `logging/ArkLogger` — app-wide logger; mirrors warnings/errors to
  an on-device file in addition to Logcat.

Beyond loading the SPA, this also:

- Goes truly edge-to-edge in fullscreen — hides the status bar,
  nav bar (gesture pill or 3-button), *and* draws under the
  notch/camera cutout.
- Keeps the screen from sleeping/locking for as long as fullscreen
  video is on screen, and restores normal behavior once fullscreen
  ends.
- Rotates fullscreen video to match the *video's* own orientation,
  overriding the phone's system auto-rotate lock, and restores
  whatever orientation you were in before once fullscreen ends.
- Hides the target SPA's own "open app"/install nag, if the active
  flavor has configured selectors/text for it — a no-op otherwise.

See `../docs/bugs-caught/` for the current active-bug list before
touching any of the above.

- `MediaPlaybackService.kt` — a foreground service hosting a real
  `MediaSessionCompat` and `MediaStyle` notification, so play/pause/
  seek/±10s reach the page's `<video>` element from *outside* the app
  entirely: the lock screen, the notification shade, a wired
  headset's inline remote, a Bluetooth earbud/car-stereo's AVRCP
  buttons, a paired watch — anything the OS considers a device that
  can control the active media session. Only promoted into the
  foreground the first time real playback is reported, not eagerly on
  launch. Handles audio focus (pauses on a call/other player taking
  over) and headphone-unplug, same as any other media app.
- `ArkwareApplication.kt` — enables Material You dynamic color on
  Android 12+; only affects the splash screen and system bars, since
  the WebView content is the target SPA's own theming.
- `res/` — a vector-only adaptive icon plus raster PNG fallbacks for
  API 24-25, which predate adaptive icons.
- `../.github/workflows/android-build.yml` (at the repo root, not in
  this directory — GitHub Actions only discovers workflow files at a
  repo's top level) — builds a debug APK, smoke-tests it (install +
  launch), and builds a release APK, all on GitHub-hosted runners on
  every push/PR that touches `android-project/`. No JDK, Android SDK,
  or emulator/device needed on your own machine.

The release build is unsigned unless you add `RELEASE_KEYSTORE_BASE64`
(your keystore file, base64-encoded), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` as secrets in this
repo's Settings → Secrets and variables → Actions — if you do, the
workflow decodes and uses them to sign the APK; if not, the job still
succeeds and gives you an unsigned APK you can sign yourself later.

## v1 status

Tracked against [`docs/Foundational/ROADMAP.md`](../docs/Foundational/ROADMAP.md)'s
v1 "done when" list:

- [x] Config-driven target SPA via Gradle product flavors, not
      hardcoded to one site
- [x] JS / DOM storage / cookies enabled (so login persists)
- [x] In-app back button walks WebView history before exiting
- [x] Fullscreen video works (`WebChromeClient` custom-view hooks),
      true edge-to-edge, cropped to fill rather than letterboxed,
      oriented to match the video's own shape regardless of the
      rotation lock, and keeps the screen awake for as long as it's
      on screen
- [x] Media-session/notification playback controls (play/pause/seek
      from the lock screen, notification shade, wired headset, or a
      Bluetooth device's own transport buttons)
- [ ] Playback verification on a real device, per flavor
- [ ] Authentication / session-persistence verification, per flavor
- [ ] Picture-in-picture
- [ ] Any persistent native chrome (nav shell, sidebar)
- [ ] Download interception, ad-blocking, or other content changes

Everything unchecked is explicitly out of scope for this stage. A
per-SPA notification-inbox poller (what ARKtube's own
`NotificationSyncWorker` did for YouTube specifically) is not carried
over here either — it's markup-specific to one SPA rather than a
generic shell affordance, and isn't part of ARKware v1's scope.

## Why not the SPA's desktop layout

A phone-sized WebView showing a desktop-only layout produces a
squeezed, zoomed-out result rather than a usable mobile one.
`ArkWebViewFactory` identifies as a mobile browser
(`useWideViewPort`/`loadWithOverviewMode`, and stripping `; wv` from
the user agent) specifically so a responsive SPA serves its own mobile
layout instead.

## CI

[`../.github/workflows/android-build.yml`](../.github/workflows/android-build.yml)
builds a debug APK per flavor, smoke-tests it (install + launch on a
headless emulator), and builds a release APK, on every push/PR that
touches `android-project/`. See that file's header comment for the
optional signing secrets. Licensed under
[GPL-3.0-or-later](../LICENSE), same as the rest of the repo.
