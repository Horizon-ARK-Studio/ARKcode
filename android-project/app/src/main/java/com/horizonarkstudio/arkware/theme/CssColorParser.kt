package com.horizonarkstudio.arkware.theme

import android.graphics.Color

/**
 * Parses a CSS `getComputedStyle` color string ("rgb(r, g, b)" or
 * "rgba(r, g, b, a)" -- the form the browser always normalizes to,
 * regardless of how the color was originally authored) into an
 * Android color int. Returns null for anything unparseable or fully
 * transparent, since a transparent background isn't a real color to
 * sync the status bar to.
 */
object CssColorParser {

    fun parse(css: String?): Int? {
        if (css == null) return null
        val components = Regex("[\\d.]+").findAll(css)
            .mapNotNull { it.value.toFloatOrNull() }
            .toList()
        if (components.size < 3) return null
        val alpha = if (components.size > 3) components[3] else 1f
        if (alpha <= 0f) return null
        val r = components[0].toInt().coerceIn(0, 255)
        val g = components[1].toInt().coerceIn(0, 255)
        val b = components[2].toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
