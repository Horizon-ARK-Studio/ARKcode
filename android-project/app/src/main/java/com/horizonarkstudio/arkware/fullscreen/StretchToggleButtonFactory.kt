package com.horizonarkstudio.arkware.fullscreen

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/**
 * GoF Factory: builds the manual stretch-to-fill toggle button.
 * Exposed as a small persistent overlay for the duration of
 * fullscreen, since the automatic crop in [ZoomCropStrategy] only
 * engages itself when it measures real letterbox/pillarbox bars.
 */
object StretchToggleButtonFactory {

    private const val BG_COLOR = 0x66000000 // translucent black

    fun create(context: Context, forceFillEnabled: Boolean, onToggle: () -> Unit): Button {
        val button = Button(context)
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.textSize = 11f
        button.setPadding(0, 0, 0, 0)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BG_COLOR)
        }
        button.setOnClickListener { onToggle() }
        applyAppearance(button, forceFillEnabled)
        return button
    }

    fun applyAppearance(button: Button, forceFillEnabled: Boolean) {
        // forceFillEnabled here means "unscaled override active" (see
        // FullscreenVideoController.applyZoomCrop()) -- checked state
        // is the escape hatch back to Chromium's real controls, not
        // "more aggressive crop" as the preference's name still implies.
        button.text = if (forceFillEnabled) "FIT\u2713" else "FILL"
        button.alpha = if (forceFillEnabled) 1f else 0.6f
    }
}
