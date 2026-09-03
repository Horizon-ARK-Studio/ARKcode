package com.arkware.ide.backend

/**
 * What [IdeBackendService] has to tell [com.arkware.ide.MainActivity]:
 * either it hasn't finished booting yet, it's listening on a real
 * loopback port the WebView can now be pointed at, or it failed to
 * start at all (e.g. the placeholder/eventual real backend process
 * couldn't bind a socket). [MainActivity] stays on the static
 * WebViewAssetLoader loading page (see MainActivity.kt) for
 * [Starting] and [Failed], and only navigates away from it on
 * [Ready] -- see VSCODE-IDE-IMPLEMENTATION-PLAN.md section 4.2,
 * "hand it to MainActivity via a bound-service callback."
 */
sealed class IdeBackendState {
    object Starting : IdeBackendState()
    data class Ready(val port: Int) : IdeBackendState()
    data class Failed(val reason: String) : IdeBackendState()
}
