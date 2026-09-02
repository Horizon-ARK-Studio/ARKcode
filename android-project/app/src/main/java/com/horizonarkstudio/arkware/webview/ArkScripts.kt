package com.horizonarkstudio.arkware.webview

/**
 * Every piece of JS this app injects into the target SPA, in one
 * place -- see each constant's/function's own comment for what it
 * does and why it has to be JS rather than native code.
 *
 * Everything here is generic: nothing below assumes a particular
 * SPA's markup. The two behaviors that genuinely can't be generic
 * ([nagHideJs]'s CSS selectors/button text, and the page-title suffix
 * [mediaSessionJs] strips) take that per-SPA detail as a parameter,
 * supplied by the caller from [com.horizonarkstudio.arkware.config.SpaConfig]
 * -- which is itself generated per Gradle product flavor -- rather
 * than being baked in here.
 */
object ArkScripts {

    // Dispatches synthetic resize/orientationchange events so any of
    // the page's own listeners for those *specific* DOM events fire,
    // even though the WebView's own resize (a real, correct Chromium
    // layout change) doesn't reliably trigger them the way an actual
    // browser window resize would. The small scroll nudge that's the
    // other half of that fix happens natively (webView.scrollBy), not
    // here, since it needs to move the *Android* View's scroll
    // position, not just fire a page-side event.
    const val FORCE_REFLOW_JS = """
        (function() {
            window.dispatchEvent(new Event('resize'));
            window.dispatchEvent(new Event('orientationchange'));
        })();
    """

    // Reports the fullscreen stream's own intrinsic pixel size
    // (video.videoWidth/videoHeight) to native code (ArkOrientation)
    // whenever it changes. This is *all* this JS does -- the actual
    // zoom-to-fill crop lives entirely in Kotlin, since once YouTube's
    // player goes fullscreen, WebView promotes it out of the DOM onto
    // a separate, hardware-composited native View/SurfaceView that
    // page CSS has no way to reach at all.
    const val VIDEO_SIZE_REPORT_JS = """
        (function() {
            if (window.__arkVideoSizeReportInstalled) { return; }
            window.__arkVideoSizeReportInstalled = true;

            var lastReportedW = 0;
            var lastReportedH = 0;

            function fullscreenVideo() {
                var el = document.fullscreenElement || document.webkitFullscreenElement;
                if (!el) { return null; }
                return el.tagName === 'VIDEO' ? el : el.querySelector('video');
            }

            function reportSize() {
                var video = fullscreenVideo();
                if (!video) { return; }

                var videoW = video.videoWidth;
                var videoH = video.videoHeight;
                if (!videoW || !videoH) { return; }
                if (videoW === lastReportedW && videoH === lastReportedH) { return; }
                lastReportedW = videoW;
                lastReportedH = videoH;
                if (window.ArkOrientation) {
                    window.ArkOrientation.onFullscreenVideoSize(videoW, videoH);
                }
            }

            var pending = false;
            function scheduleReportSize() {
                if (pending) { return; }
                pending = true;
                requestAnimationFrame(function() {
                    pending = false;
                    reportSize();
                });
            }

            document.addEventListener('fullscreenchange', scheduleReportSize);
            document.addEventListener('webkitfullscreenchange', scheduleReportSize);
            window.addEventListener('resize', scheduleReportSize);
            document.addEventListener('fullscreenchange', function() {
                lastReportedW = 0;
                lastReportedH = 0;
                setTimeout(scheduleReportSize, 300);
                setTimeout(scheduleReportSize, 1000);
            });
            document.addEventListener('loadedmetadata', function(e) {
                if (e.target === fullscreenVideo()) {
                    scheduleReportSize();
                }
            }, true);
        })();
    """

    // Reports the SPA's actual rendered page background back to
    // ThemeBridge, so the status/nav bar can match *the page's own*
    // light/dark toggle specifically, not the phone's system theme.
    //
    // report() is wired to two triggers: a MutationObserver on
    // <html>/<body> attributes (YouTube's SPA toggles classes on both
    // constantly -- scroll-lock, player-active, ad states, etc.) and
    // a 2000ms setInterval as a backstop for changes the observer
    // might miss. Both call report() far more often than the theme
    // actually changes, which is exactly why -- unlike this same
    // function in an earlier revision -- it now checks
    // lastReportedDark/lastReportedBg before calling the bridge at
    // all: see BUG-0001 in docs/bugs-caught/. Without that guard this
    // was posting an identical onThemeChanged() call to native code
    // roughly every 2 seconds (matching BUG-0001's observed decoder-
    // churn cadence almost exactly) even when nothing about the theme
    // had changed, and StatusBarThemeApplier.apply() unconditionally
    // rewrote window.statusBarColor/navigationBarColor and the
    // WindowInsetsController appearance flags on every single one of
    // those calls -- the same "operation that's only safe once per
    // state transition, instead re-run on every repeated report"
    // shape as the SurfaceView z-order bug and the AUDIOFOCUS_GAIN
    // bug elsewhere in this app (see MediaPlaybackService's
    // updatePlaybackState() doc comment), just not yet given the same
    // dedupe treatment VIDEO_SIZE_REPORT_JS/MEDIA_SESSION_JS's own
    // report functions already have.
    const val THEME_SYNC_JS = """
        (function() {
            if (window.__arkThemeSyncInstalled) { return; }
            window.__arkThemeSyncInstalled = true;

            var lastReportedDark = null;
            var lastReportedBg = null;

            function readBackground(el) {
                if (!el) { return null; }
                return window.getComputedStyle(el).backgroundColor;
            }

            function isDark(rgbaString) {
                if (!rgbaString) { return null; }
                var nums = rgbaString.match(/[\d.]+/g);
                if (!nums || nums.length < 3) { return null; }
                var alpha = nums.length > 3 ? parseFloat(nums[3]) : 1;
                if (alpha === 0) { return null; }
                var r = parseFloat(nums[0]);
                var g = parseFloat(nums[1]);
                var b = parseFloat(nums[2]);
                var luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                return luminance < 128;
            }

            function report() {
                var bg = readBackground(document.body);
                var dark = isDark(bg);
                if (dark === null) {
                    bg = readBackground(document.documentElement);
                    dark = isDark(bg);
                }
                if (dark === null || !window.ArkTheme) { return; }
                // The dedupe check this function was missing: skip
                // the native call entirely when neither value has
                // actually changed since the last report, regardless
                // of which trigger (interval or mutation) fired.
                if (dark === lastReportedDark && bg === lastReportedBg) { return; }
                lastReportedDark = dark;
                lastReportedBg = bg;
                window.ArkTheme.onThemeChanged(dark, bg);
            }

            report();

            var observer = new MutationObserver(report);
            observer.observe(document.documentElement, { attributes: true });
            if (document.body) {
                observer.observe(document.body, { attributes: true });
            }

            setInterval(report, 2000);
        })();
    """

    /**
     * Hides an SPA's own "open app"/"install our app" nag button or
     * banner, since this shell already *is* that experience, just
     * wrapped natively -- when the target SPA has one, and the flavor
     * building this app has told us how to find it.
     *
     * Unlike the rest of this object, there is no SPA-agnostic version
     * of "which element is the nag" -- it's markup-specific by nature
     * -- so [cssSelectors]/[buttonTextMatches] come from
     * [com.horizonarkstudio.arkware.config.SpaConfig], which is
     * itself sourced from the active Gradle product flavor (see
     * `NAG_HIDE_SELECTORS`/`NAG_HIDE_TEXT_MATCHES` in
     * `app/build.gradle.kts`). A flavor that leaves both empty (the
     * default for a newly scaffolded SPA) gets an empty, harmless
     * no-op script back -- it does not inherit any other SPA's
     * selectors.
     */
    fun nagHideJs(cssSelectors: List<String>, buttonTextMatches: List<String>): String {
        if (cssSelectors.isEmpty() && buttonTextMatches.isEmpty()) return ""

        val cssSelectorList = cssSelectors.joinToString(", ") { jsStringLiteral(it) }
        val buttonTextList = buttonTextMatches.joinToString(", ") { jsStringLiteral(it.lowercase()) }

        return """
            (function() {
                if (window.__arkHideOpenAppInstalled) { return; }
                window.__arkHideOpenAppInstalled = true;

                var CSS_SELECTORS = [$cssSelectorList];
                var BUTTON_TEXT_MATCHES = [$buttonTextList];

                var STYLE_ID = 'ark-hide-open-app';
                if (CSS_SELECTORS.length && !document.getElementById(STYLE_ID)) {
                    var style = document.createElement('style');
                    style.id = STYLE_ID;
                    style.textContent = CSS_SELECTORS.join(', ') + ' { display: none !important; }';
                    document.head.appendChild(style);
                }

                function hideByText() {
                    if (!BUTTON_TEXT_MATCHES.length) { return; }
                    var candidates = document.querySelectorAll('button, a');
                    candidates.forEach(function(el) {
                        var text = (el.textContent || '').trim().toLowerCase();
                        if (BUTTON_TEXT_MATCHES.indexOf(text) !== -1) {
                            el.style.display = 'none';
                        }
                    });
                }

                hideByText();
                setInterval(hideByText, 1500);
            })();
        """
    }

    /** Minimal JS string-literal escaping for values sourced from build config. */
    private fun jsStringLiteral(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    const val MEDIA_CONTROL_PLAY_JS = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.play(); }
        })();
    """

    const val MEDIA_CONTROL_PAUSE_JS = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.pause(); }
        })();
    """

    fun mediaControlSeekJs(positionMs: Long): String = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.currentTime = ${positionMs / 1000.0}; }
        })();
    """

    fun mediaControlSkipJs(deltaSeconds: Int): String = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.currentTime = Math.max(0, v.currentTime + ($deltaSeconds)); }
        })();
    """

    /**
     * Watches whichever `<video>` element the page is actually
     * playing through and reports play/pause state, position, title,
     * and artwork back to MediaPlaybackBridge -- see MainActivity's
     * class doc for the full MediaSessionCompat picture this feeds
     * into.
     *
     * [siteNameSuffix] is the only SPA-specific piece: many sites
     * append their own name to `document.title` (e.g. "Video Title -
     * YouTube"), which would otherwise leak into the media
     * notification's title. When non-blank, a trailing
     * `" - <siteNameSuffix>"` is stripped; a blank value (an SPA that
     * doesn't do this) leaves the title untouched.
     */
    fun mediaSessionJs(siteNameSuffix: String): String {
        val titleSuffixRegex = if (siteNameSuffix.isNotBlank()) {
            "/\\s*-\\s*" + jsRegexEscape(siteNameSuffix) + "\\s*$/"
        } else {
            "null"
        }
        return """
        (function() {
            if (window.__arkMediaSessionInstalled) { return; }
            window.__arkMediaSessionInstalled = true;

            var attachedVideo = null;
            var lastReportedTitle = null;
            var lastTimeUpdateReportAt = 0;
            var TITLE_SUFFIX_PATTERN = $titleSuffixRegex;

            function pageTitle() {
                var t = document.title || '';
                if (!TITLE_SUFFIX_PATTERN) { return t; }
                var stripped = t.replace(TITLE_SUFFIX_PATTERN, '');
                return stripped || t;
            }

            function artworkUrl() {
                var og = document.querySelector('meta[property="og:image"]');
                return og ? og.getAttribute('content') : null;
            }

            function reportInfo() {
                var video = attachedVideo;
                if (!video || !window.ArkMediaPlayback) { return; }
                var title = pageTitle();
                if (title === lastReportedTitle) { return; }
                lastReportedTitle = title;
                var durationMs = isFinite(video.duration) ? Math.round(video.duration * 1000) : 0;
                window.ArkMediaPlayback.onMediaInfo(title, durationMs, artworkUrl());
            }

            function reportState() {
                var video = attachedVideo;
                if (!video || !window.ArkMediaPlayback) { return; }
                window.ArkMediaPlayback.onPlaybackState(
                    !video.paused && !video.ended,
                    Math.round(video.currentTime * 1000),
                    video.playbackRate || 1
                );
            }

            function reportStateThrottled() {
                var now = Date.now();
                if (now - lastTimeUpdateReportAt < 1000) { return; }
                lastTimeUpdateReportAt = now;
                reportState();
            }

            function attach(video) {
                if (!video || video === attachedVideo) { return; }
                attachedVideo = video;
                lastReportedTitle = null;
                ['play', 'pause', 'seeked', 'ended'].forEach(function(evt) {
                    video.addEventListener(evt, reportState);
                });
                video.addEventListener('timeupdate', reportStateThrottled);
                video.addEventListener('loadedmetadata', reportInfo);
                video.addEventListener('durationchange', reportInfo);
                reportState();
                reportInfo();
            }

            function findVideo() {
                attach(document.querySelector('video'));
            }

            findVideo();
            var observer = new MutationObserver(findVideo);
            observer.observe(document.body, { childList: true, subtree: true });
            document.addEventListener('loadedmetadata', function(e) {
                if (e.target && e.target.tagName === 'VIDEO') { attach(e.target); }
            }, true);
        })();
        """
    }

    /** Escapes JS regex metacharacters so [value] can be embedded inside a `/.../` JS regex literal. */
    private fun jsRegexEscape(value: String): String {
        val metaChars = ".*+?^\$(){}|[]\\"
        return value.map { c -> if (c in metaChars) "\\$c" else c.toString() }.joinToString("")
    }
}
