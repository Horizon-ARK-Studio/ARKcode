package com.horizonarkstudio.arkware.fullscreen

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Strips any SurfaceView inside a view tree of the z-order flags that
 * let it paint above the rest of the window (Chromium's fullscreen
 * customView is backed by a hardware-composited SurfaceView with
 * setZOrderOnTop/setZOrderMediaOverlay commonly left on, which would
 * otherwise sit in front of native overlays like the stretch-to-fill
 * button regardless of normal View add-order).
 *
 * IMPORTANT: must only run its setters once per fullscreen session --
 * see [neutralize]'s own doc for why re-invoking on an
 * already-neutralized, actively-rendering Surface causes fullscreen
 * video to pause/resume in a loop.
 */
object SurfaceViewZOrderNeutralizer {

    private const val COMPONENT = "SurfaceViewZOrderNeutralizer"

    /** Returns true if a SurfaceView was found (and therefore acted on) anywhere in [root]. */
    fun neutralize(root: View): Boolean = try {
        var found = false
        if (root is SurfaceView) {
            root.setZOrderOnTop(false)
            root.setZOrderMediaOverlay(false)
            found = true
            ArkLogger.d(COMPONENT, "Neutralized z-order on a SurfaceView")
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (neutralize(root.getChildAt(i))) found = true
            }
        }
        found
    } catch (t: Throwable) {
        ArkLogger.e(COMPONENT, "neutralize() failed", t)
        false
    }
}
