package com.horizonarkstudio.arkware.config

import com.horizonarkstudio.arkware.BuildConfig

/**
 * The single place that reads the per-SPA values baked in at build
 * time by a Gradle product flavor (see `productFlavors` in
 * `app/build.gradle.kts`) -- everything the generic shell needs to
 * know to point itself at *this* build's target SPA instead of any
 * other.
 *
 * Nothing outside this file should reference [BuildConfig] fields
 * directly, and nothing outside a flavor definition should hardcode
 * a URL, a display name, or a DOM selector belonging to a specific
 * SPA -- that's exactly the "copy-pasted with the YouTube specifics
 * left in" shape `docs/Foundational/ROADMAP.md`'s v1 section calls
 * out as the thing this generalization has to avoid.
 *
 * [nagHideSelectors]/[nagHideTextMatches] are deliberately optional:
 * a flavor with nothing configured for them means "don't try to hide
 * an install/open-app nag on this SPA at all" (see
 * `ArkScripts.nagHideJs`), rather than every new SPA silently
 * inheriting YouTube's own markup-specific selectors.
 */
object SpaConfig {

    val targetUrl: String = BuildConfig.TARGET_URL
    val displayName: String = BuildConfig.SPA_DISPLAY_NAME
    val nagHideSelectors: List<String> = splitConfigList(BuildConfig.NAG_HIDE_SELECTORS)
    val nagHideTextMatches: List<String> = splitConfigList(BuildConfig.NAG_HIDE_TEXT_MATCHES)

    private fun splitConfigList(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
