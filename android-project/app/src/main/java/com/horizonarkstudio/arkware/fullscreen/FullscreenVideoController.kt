package com.horizonarkstudio.arkware.fullscreen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.prefs.ForceFillPreference

/**
 * Facade (GoF Facade) that owns everything about fullscreen video:
 *
 *  - hosting Chromium's customView (WebChromeClient.onShowCustomView/
 *    onHideCustomView) inside [rootLayout] as a second child on top of
 *    the WebView, which itself is never detached (keeping it attached
 *    is what avoids YouTube's player seeing `document.hidden = true`
 *    and immediately exiting fullscreen again)
 *  - neutralizing the fullscreen SurfaceView's z-order so the native
 *    stretch-to-fill button can actually paint/receive touches above it
 *  - applying the zoom-to-fill crop via [zoomCropStrategy]
 *  - locking rotation to the video's own intrinsic orientation
 *  - immersive system bars for the duration of fullscreen
 *
 * Constructed once per Activity instance and handed the pieces it
 * needs (the activity for window/resources access, the always-present
 * root container, and the persisted force-fill preference) rather
 * than reaching for globals.
 */
class FullscreenVideoController(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val forceFillPreference: ForceFillPreference,
    private val zoomCropStrategy: ZoomCropStrategy = LetterboxZoomCropStrategy(),
    // Called once native fullscreen teardown is complete, so the caller
    // can force a page-side reflow (see LayoutReflowHelper) right at the
    // moment we *know* the exit happened -- instead of only reacting if
    // and when Android happens to also deliver an onConfigurationChanged()
    // callback afterward, which is a real gap: YouTube's own player-mode
    // JS (not just the "Up next" row LayoutReflowHelper was originally
    // written for) can be left believing it's still in the landscape/
    // fullscreen layout if that callback doesn't fire, or fires before
    // this teardown is actually done.
    private val onExitFullscreen: () -> Unit = {}
) {

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var stretchToggleButton: android.widget.Button? = null
    private var surfaceViewZOrderNeutralized = false
    private var preFullscreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    val isShowing: Boolean get() = customView != null

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        ArkLogger.track(COMPONENT, "showCustomView") {
            if (customView != null) {
                callback.onCustomViewHidden()
                return@track
            }
            customView = view
            customViewCallback = callback
            surfaceViewZOrderNeutralized = SurfaceViewZOrderNeutralizer.neutralize(view)

            val container = buildFullscreenContainer(view)
            fullscreenContainer = container
            rootLayout.addView(
                container,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            attachStretchToggleButton()

            preFullscreenOrientation = activity.requestedOrientation
            enterImmersiveFullscreen()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyZoomCrop()
        }
    }

    fun hideCustomView() {
        ArkLogger.track(COMPONENT, "hideCustomView") {
            fullscreenContainer?.let { rootLayout.removeView(it) }
            fullscreenContainer?.removeAllViews()
            fullscreenContainer = null
            stretchToggleButton?.let { rootLayout.removeView(it) }
            stretchToggleButton = null
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            surfaceViewZOrderNeutralized = false
            lastVideoWidth = 0
            lastVideoHeight = 0
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exitImmersiveFullscreen()
            activity.requestedOrientation = preFullscreenOrientation
            onExitFullscreen()
        }
    }

    /** Called by OrientationBridge via MainActivity when VIDEO_SIZE_REPORT_JS reports a new size. */
    fun onFullscreenVideoSize(width: Int, height: Int) {
        ArkLogger.track(COMPONENT, "onFullscreenVideoSize($width,$height)") {
            lastVideoWidth = width
            lastVideoHeight = height
            applyOrientationLock(width, height)
            applyZoomCrop()
        }
    }

    /** Reasserts immersive mode on window-focus regain -- see the original class doc for why. */
    fun onWindowFocusRegained() {
        if (isShowing) enterImmersiveFullscreen()
    }

    fun toggleForceFill() {
        ArkLogger.track(COMPONENT, "toggleForceFill") {
            val enabled = forceFillPreference.toggle()
            applyZoomCrop()
            stretchToggleButton?.let { StretchToggleButtonFactory.applyAppearance(it, enabled) }
        }
    }

    private fun buildFullscreenContainer(video: View): FrameLayout {
        val container = FrameLayout(activity)
        container.addView(
            video,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        container.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                if (!surfaceViewZOrderNeutralized) {
                    surfaceViewZOrderNeutralized = SurfaceViewZOrderNeutralizer.neutralize(container)
                }
                applyZoomCrop()
            } catch (t: Throwable) {
                ArkLogger.e(COMPONENT, "Global layout listener failed", t)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            try {
                applyZoomCrop()
            } catch (t: Throwable) {
                ArkLogger.e(COMPONENT, "Insets listener failed", t)
            }
            insets
        }
        return container
    }

    private fun attachStretchToggleButton() {
        val button = StretchToggleButtonFactory.create(activity, forceFillPreference.isEnabled) { toggleForceFill() }
        stretchToggleButton = button
        val buttonSizePx = dpToPx(STRETCH_BUTTON_SIZE_DP)
        val marginPx = dpToPx(STRETCH_BUTTON_MARGIN_DP)
        val buttonParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        // Added directly to rootLayout, not nested in fullscreenContainer --
        // see SurfaceViewZOrderNeutralizer's doc for why sibling order
        // inside a container wouldn't be enough on its own.
        rootLayout.addView(button, buttonParams)
    }

    /**
     * `forceFillPreference.isEnabled` is being used here as a manual
     * escape hatch back to an *unscaled* view -- not, as the name and
     * original threshold-nudge design implied, a way to crop more
     * aggressively. See the conversation/investigation notes for why:
     * `view.scaleX/scaleY` transforms the *entire* opaque Chromium
     * fullscreen surface uniformly, including any native player-control
     * chrome (captions, settings) baked into that same surface -- past
     * a real crop factor, controls anchored near the original edges get
     * pushed outside the container's clip bounds with no way to reach
     * them. The only way back is to undo the transform entirely, which
     * is what this branch does, bypassing ZoomCropStrategy altogether
     * rather than just nudging its threshold (the old behavior had no
     * path that could ever produce scale=1f once real letterboxing was
     * already being auto-cropped, so toggling it was a no-op for
     * exactly the case this button exists to solve).
     */
    private fun applyZoomCrop() {
        val view = customView ?: return
        if (forceFillPreference.isEnabled) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val containerW = view.width
        val containerH = view.height
        val result = zoomCropStrategy.compute(containerW, containerH, lastVideoWidth, lastVideoHeight, false)
        if (!result.shouldApply) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.pivotX = containerW / 2f
        view.pivotY = containerH / 2f
        view.scaleX = result.scale
        view.scaleY = result.scale
    }

    /**
     * IMPORTANT: only writes `requestedOrientation` when the *category*
     * (landscape vs. portrait) actually changes -- see BUG-0003 in
     * docs/bugs-caught/. VIDEO_SIZE_REPORT_JS's own dedupe only
     * suppresses a re-report when the reported pixel size is
     * byte-identical to the last one; an ABR resolution step (e.g.
     * 640x360 -> 1920x1080 shortly after entering fullscreen, both
     * still landscape) is a legitimate size change that JS correctly
     * re-reports, which used to reach this function and re-issue the
     * *same* SENSOR_LANDSCAPE/SENSOR_PORTRAIT value a second time
     * within ~300-1000ms of the first. Same "operation only safe once
     * per state transition, re-run on every repeated report" shape as
     * SurfaceViewZOrderNeutralizer, MediaPlaybackService.requestAudioFocus(),
     * and StatusBarThemeApplier.apply() -- this was the one place in
     * the app that pattern had been fixed everywhere else but here.
     */
    private fun applyOrientationLock(videoWidth: Int, videoHeight: Int) {
        if (!isShowing || videoWidth <= 0 || videoHeight <= 0) return
        val targetOrientation = if (videoWidth >= videoHeight) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (activity.requestedOrientation == targetOrientation) {
            ArkLogger.d(COMPONENT, "applyOrientationLock: no-op, orientation category unchanged")
            return
        }
        activity.requestedOrientation = targetOrientation
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitImmersiveFullscreen() {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        // setDecorFitsSystemWindows(true) tells Android to resume
        // auto-padding content around system bars/the display cutout,
        // but it doesn't by itself force a *new* WindowInsets dispatch --
        // the decor view keeps whatever insets it was last actually
        // handed (computed for the landscape/immersive window state)
        // until something triggers a fresh pass. A real device rotation
        // normally carries that along for free via Activity recreation,
        // but AndroidManifest.xml's `configChanges="orientation|..."`
        // deliberately opts out of recreation, so it also opts out of
        // whatever insets redispatch would have ridden along with it --
        // same reason onConfigurationChanged() has to explicitly drive
        // LayoutReflowHelper.reflow() instead of assuming it happens on
        // its own. Left unrequested, this is what shows up as a
        // leftover cutout-shaped padding strip after exiting fullscreen
        // back to portrait, that only clears on a full app restart
        // (i.e. a genuinely new window/decor view/insets dispatch).
        ViewCompat.requestApplyInsets(activity.window.decorView)
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), activity.resources.displayMetrics
    ).toInt()

    private companion object {
        const val COMPONENT = "FullscreenVideoController"
        const val STRETCH_BUTTON_SIZE_DP = 40
        const val STRETCH_BUTTON_MARGIN_DP = 16
    }
}
