package com.horizonarkstudio.arkware

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.horizonarkstudio.arkware.config.SpaConfig
import com.horizonarkstudio.arkware.fullscreen.FullscreenVideoController
import com.horizonarkstudio.arkware.layout.LayoutReflowHelper
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.media.MediaSessionCoordinator
import com.horizonarkstudio.arkware.prefs.ForceFillPreference
import com.horizonarkstudio.arkware.theme.StatusBarThemeApplier
import com.horizonarkstudio.arkware.webview.ArkPopupWindowHandler
import com.horizonarkstudio.arkware.webview.ArkScripts
import com.horizonarkstudio.arkware.webview.ArkWebViewFactory

/**
 * Generic Stage-1 shell Activity for an ARKware Android build.
 *
 * This mirrors the project's model exactly (see
 * ../../../docs/Foundational/PROBLEM-STATEMENT.md at the repo root):
 * don't redesign the target SPA, don't bundle a copy of it -- just
 * point a WebView at the real, live site (whichever one [SpaConfig]
 * says this build variant targets -- see `app/build.gradle.kts`'s
 * `productFlavors`) and let the SPA be itself.
 *
 * As of this refactor, MainActivity itself is deliberately thin: it
 * owns the Activity lifecycle and wires together a handful of
 * single-responsibility collaborators, each in its own package under
 * com.horizonarkstudio.arkware --
 *
 *  - `webview.ArkWebViewFactory` -- builds/configures the WebView
 *    and its three JS bridges (GoF Factory)
 *  - `webview.bridge.*` -- the JS bridges themselves, sharing a common
 *    `ArkJsBridge` abstract base for uniform try/catch/finally
 *    logging (GoF Abstract Class / Template Method)
 *  - `fullscreen.FullscreenVideoController` -- everything about
 *    fullscreen video: customView hosting, the SurfaceView z-order
 *    fix, the zoom-to-fill crop, immersive bars, orientation lock
 *    (GoF Facade over that whole subsystem)
 *  - `fullscreen.ZoomCropStrategy` -- the crop math itself, swappable
 *    independent of the controller (GoF Strategy)
 *  - `fullscreen.StretchToggleButtonFactory` -- builds the
 *    stretch-to-fill button (GoF Factory)
 *  - `media.MediaSessionCoordinator` -- MediaPlaybackService binding
 *    and JS transport-control dispatch
 *  - `theme.StatusBarThemeApplier` / `theme.CssColorParser` -- status
 *    bar theme sync
 *  - `layout.LayoutReflowHelper` -- the post-rotation reflow workaround
 *  - `prefs.ForceFillPreference` -- persisted stretch-to-fill state
 *  - `logging.ArkLogger` -- app-wide logger (GoF Singleton); mirrors
 *    every warning/error to an on-device file
 *    (`filesDir/--log-failed`) in addition to Logcat, so a failure can
 *    be pulled off a device after the fact even without a live
 *    debugger attached
 *
 * All the *why* behind each individual behavior (edge-to-edge
 * fullscreen, the zoom crop, MediaSessionCompat integration, theme
 * sync, the "open app" nag removal, etc.) now lives as doc comments on
 * the collaborator that actually implements it, rather than one large
 * comment block here -- see each class listed above.
 *
 * Two more collaborators cover cases the original ARKtube-only shell
 * never had to handle, needed once a heavier, non-video SPA (e.g.
 * vscode.dev) is targeted instead of a mobile video site:
 *  - `webview.ArkPopupWindowHandler` -- window.open() popups, i.e.
 *    third-party sign-in (GitHub/Microsoft/Google OAuth)
 *  - [setupDownloadListener] below -- SPA-triggered file downloads
 *    (Settings Sync export, extension .vsix downloads, etc.), routed
 *    through Android's DownloadManager instead of vanishing silently
 *
 * Explicitly out of scope even with the above (future stages, see the
 * repo-root roadmap): a persistent nav shell/sidebar, PiP, a real
 * playlist/queue or Android Auto browsing, chromecast, ad-blocking,
 * blob: URL downloads (client-generated files that never touch a real
 * URL -- would need a JS bridge to intercept, not just
 * DownloadListener, which only fires for http(s) URLs), or any custom
 * UI layered over the page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var fullscreenController: FullscreenVideoController
    private lateinit var mediaSessionCoordinator: MediaSessionCoordinator
    private lateinit var statusBarThemeApplier: StatusBarThemeApplier
    private lateinit var layoutReflowHelper: LayoutReflowHelper
    private lateinit var popupWindowHandler: ArkPopupWindowHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // ArkLogger.init() is called once from ArkwareApplication.onCreate
        // instead of here, so it's live before anything else in the
        // process (any future background component, not just this
        // Activity) could possibly need it.

        ArkLogger.track(COMPONENT, "onCreate") {
            configureWindowForCutout()

            val forceFillPreference = ForceFillPreference(this)
            statusBarThemeApplier = StatusBarThemeApplier(window)

            rootLayout = FrameLayout(this)
            setContentView(rootLayout)

            // The exit-fullscreen callback references layoutReflowHelper
            // lazily (same pattern as mediaSessionCoordinator's { webView }
            // provider just below) -- it's a lateinit var not yet assigned
            // at this point in onCreate(), but the lambda itself only runs
            // later, from hideCustomView(), by which point onCreate() has
            // finished and layoutReflowHelper definitely exists.
            fullscreenController = FullscreenVideoController(
                activity = this,
                rootLayout = rootLayout,
                forceFillPreference = forceFillPreference,
                onExitFullscreen = { layoutReflowHelper.reflow { fullscreenController.isShowing } }
            )
            // Safe to construct before webView exists: the { webView }
            // provider lambda only reads the lateinit property lazily,
            // the first time a transport command actually needs it.
            mediaSessionCoordinator = MediaSessionCoordinator(this) { webView }

            popupWindowHandler = ArkPopupWindowHandler(this)

            webView = ArkWebViewFactory.create(
                context = this,
                themeListener = { isDark, cssBackground ->
                    runOnUiThread {
                        ArkLogger.track(COMPONENT, "themeListener") {
                            statusBarThemeApplier.apply(isDark, cssBackground)
                        }
                    }
                },
                orientationListener = { width, height ->
                    runOnUiThread { fullscreenController.onFullscreenVideoSize(width, height) }
                },
                mediaPlaybackListener = mediaSessionCoordinator,
                webChromeClient = buildWebChromeClient()
            )
            // webView is added to rootLayout here and never removed or
            // detached again for the rest of the Activity's life -- see
            // FullscreenVideoController's class doc for why keeping it
            // permanently attached (just visually covered during
            // fullscreen) matters for the SPA's Page Visibility handling.
            rootLayout.addView(
                webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )

            layoutReflowHelper = LayoutReflowHelper(webView)

            setupDownloadListener()

            webView.loadUrl(SpaConfig.targetUrl)

            // Only a *bound* (not yet foreground) service at this point --
            // binding early just gets the command listener wired up
            // before the user could possibly reach a play button.
            mediaSessionCoordinator.bind()

            requestNotificationPermissionIfNeeded()
            requestLegacyStoragePermissionIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ArkLogger.track(COMPONENT, "onDestroy") {
            mediaSessionCoordinator.unbind()
        }
    }

    /**
     * AndroidManifest.xml declares MainActivity `launchMode="singleTask"`
     * specifically so re-launching the app (e.g. tapping the media
     * playback notification) while it's already running routes here
     * instead of onCreate() building a second instance on top of it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ArkLogger.track(COMPONENT, "onNewIntent") {
            setIntent(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        ArkLogger.track(COMPONENT, "onBackPressed") {
            when {
                fullscreenController.isShowing -> fullscreenController.hideCustomView()
                webView.canGoBack() -> webView.goBack()
                else -> super.onBackPressed()
            }
        }
    }

    /**
     * AndroidManifest.xml declares
     * `android:configChanges="orientation|screenSize|keyboardHidden"`
     * so a rotation doesn't tear down/recreate this Activity -- which
     * also means the off-screen-content reflow workaround has to be
     * hooked here explicitly. See LayoutReflowHelper's own doc for why
     * it's needed at all.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ArkLogger.track(COMPONENT, "onConfigurationChanged") {
            layoutReflowHelper.reflow { fullscreenController.isShowing }
        }
    }

    /**
     * Reasserts immersive fullscreen on window-focus regain -- see
     * FullscreenVideoController.onWindowFocusRegained()'s doc for why
     * (Android silently redraws system bars on any focus churn,
     * including a brief refocus an in-page settings menu can cause).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ArkLogger.track(COMPONENT, "onWindowFocusChanged") {
                fullscreenController.onWindowFocusRegained()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification permission is only needed to actually *show* the
        // media notification (Android 13+) -- MediaSessionCompat itself
        // works regardless of whether this is granted, so there's
        // nothing else gated on the result; this override exists purely
        // so the outcome is logged for observability.
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ArkLogger.i(COMPONENT, "POST_NOTIFICATIONS permission result: granted=$granted")
        }
        // Same "log only, nothing else gated on it here" shape as
        // notifications above -- a denial just means setupDownloadListener's
        // own runtime check will skip and toast the next time a download
        // is actually triggered, rather than anything failing right now.
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ArkLogger.i(COMPONENT, "WRITE_EXTERNAL_STORAGE permission result: granted=$granted")
        }
    }

    /**
     * Fullscreen video support: an SPA's video player swaps in a
     * custom fullscreen view via these callbacks. Without handling
     * them, the in-page fullscreen button is a dead click. All the
     * actual work is delegated to FullscreenVideoController.
     */
    private fun buildWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
            ArkLogger.track(COMPONENT, "onShowCustomView") {
                webView.evaluateJavascript(ArkScripts.VIDEO_SIZE_REPORT_JS, null)
                fullscreenController.showCustomView(view, callback)
            }
        }

        override fun onHideCustomView() {
            ArkLogger.track(COMPONENT, "onHideCustomView") {
                fullscreenController.hideCustomView()
            }
        }

        // window.open() -- most commonly a third-party sign-in popup
        // (GitHub/Microsoft/Google OAuth). Requires
        // javaScriptCanOpenWindowsAutomatically/setSupportMultipleWindows
        // (see ArkWebViewFactory.configureSettings) to fire at all; the
        // actual popup hosting is delegated to [popupWindowHandler] --
        // see its class doc for why a Dialog rather than rootLayout.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean = ArkLogger.track(COMPONENT, "onCreateWindow") {
            popupWindowHandler.createPopupWindow(resultMsg)
        }

        // The popup calling `window.close()` itself once its flow is
        // done (the other exit path -- redirecting back to the
        // opener's own origin instead -- is handled inside
        // ArkPopupWindowHandler directly, since that one isn't
        // signaled through WebChromeClient at all).
        override fun onCloseWindow(window: WebView) {
            ArkLogger.track(COMPONENT, "onCloseWindow") {
                popupWindowHandler.closePopupWindow()
            }
        }
    }

    /**
     * Routes SPA-triggered file downloads (Settings Sync export, an
     * extension's .vsix, etc.) through Android's DownloadManager
     * instead of them silently vanishing -- a bare WebView has no
     * default handling for a triggered download at all; without a
     * DownloadListener, the request just disappears with nothing in
     * Logcat to explain why.
     *
     * Only covers http(s) URL downloads. Does NOT cover blob: URL
     * downloads (client-generated files that never touch a real
     * network URL -- e.g. some "export" buttons that build the file
     * in JS and hand the browser a Blob) -- DownloadListener only
     * fires for genuine URL-based downloads; a blob: download would
     * need a dedicated JS bridge to intercept the Blob and hand its
     * bytes to native code instead.
     */
    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            ArkLogger.track(COMPONENT, "onDownloadStart") {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED
                ) {
                    ArkLogger.w(COMPONENT, "onDownloadStart: WRITE_EXTERNAL_STORAGE not granted, skipping download for $url")
                    Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                    return@track
                }
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent)
                        setTitle(fileName)
                        setDescription(getString(R.string.downloading_file))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(this, getString(R.string.downloading_file), Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    ArkLogger.e(COMPONENT, "Failed to start download for $url", t)
                    Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Lets fullscreen video draw under the notch/camera cutout instead
     * of the SPA's custom view being letterboxed around it. Must be
     * set on the window's LayoutParams directly (not just the insets
     * controller) or the cutout area stays reserved regardless of what
     * onShowCustomView does later.
     */
    private fun configureWindowForCutout() {
        ArkLogger.track(COMPONENT, "configureWindowForCutout") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        ArkLogger.track(COMPONENT, "requestNotificationPermissionIfNeeded") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * WRITE_EXTERNAL_STORAGE (see AndroidManifest.xml) is a dangerous
     * permission requiring a runtime grant on API 24-28, the range it
     * still applies to here -- API 29+ never needs it at all (scoped
     * storage exempts DownloadManager's public-dir writes), hence the
     * upper SDK_INT bound matching the manifest's maxSdkVersion.
     */
    private fun requestLegacyStoragePermissionIfNeeded() {
        ArkLogger.track(COMPONENT, "requestLegacyStoragePermissionIfNeeded") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    companion object {
        private const val COMPONENT = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1002
    }
}
