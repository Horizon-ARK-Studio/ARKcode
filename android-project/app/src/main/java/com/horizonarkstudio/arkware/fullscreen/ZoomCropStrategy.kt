package com.horizonarkstudio.arkware.fullscreen

/** The scale to apply to a fullscreen customView, and whether it should be applied at all. */
data class ZoomCropResult(val scale: Float, val shouldApply: Boolean)

/**
 * GoF Strategy: computes the crop-to-fill scale factor for fullscreen
 * video. Swappable independent of FullscreenVideoController -- e.g. a
 * future "always letterbox" build variant could supply a
 * no-op implementation without touching any view code.
 */
interface ZoomCropStrategy {
    fun compute(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        forceFillEnabled: Boolean
    ): ZoomCropResult
}

/**
 * Default strategy, ported unchanged from the original
 * `applyNativeZoomCrop()`: scales the fullscreen customView up around
 * its own center until its short axis matches the container, cropping
 * away whatever letterbox/pillarbox bars YouTube's player painted
 * into that View.
 *
 * `forceFillEnabled` lowers the no-op threshold from "letterbox big
 * enough to bother cropping" down to "basically any letterbox at
 * all", so an explicit user request (the stretch-to-fill button)
 * still does something even for an aspect ratio that's a near-exact
 * match already.
 */
class LetterboxZoomCropStrategy : ZoomCropStrategy {

    override fun compute(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        forceFillEnabled: Boolean
    ): ZoomCropResult {
        if (containerWidth <= 0 || containerHeight <= 0) return ZoomCropResult(1f, false)
        if (videoWidth <= 0 || videoHeight <= 0) return ZoomCropResult(1f, false)

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()

        val fittedW: Float
        val fittedH: Float
        if (videoAspect > containerAspect) {
            fittedW = containerWidth.toFloat()
            fittedH = containerWidth / videoAspect
        } else {
            fittedH = containerHeight.toFloat()
            fittedW = containerHeight * videoAspect
        }
        if (fittedW <= 0f || fittedH <= 0f) return ZoomCropResult(1f, false)

        val scale = maxOf(containerWidth / fittedW, containerHeight / fittedH)
        val threshold = if (forceFillEnabled) 1.001f else 1.01f
        if (!scale.isFinite() || scale <= threshold) return ZoomCropResult(1f, false)

        return ZoomCropResult(scale, true)
    }
}
