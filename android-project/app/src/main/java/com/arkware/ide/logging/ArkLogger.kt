package com.arkware.ide.logging

import android.util.Log

/**
 * Single global failure log, reachable without threading a reference
 * through every constructor -- same justification as ARKtube's own
 * `ArkLogger` (see CODE-STYLE.md section 3): a background process
 * boundary (here, [com.arkware.ide.backend.IdeBackendService]'s child
 * Node process, once it exists) can fail silently in ways that never
 * surface as a normal crash. try/catch blocks around anything crossing
 * that boundary should log here, not swallow the exception.
 *
 * A Kotlin `object` is the correct tool for exactly the same reason it
 * was in ARKtube: this is a Singleton because there is genuinely one
 * log sink for the whole process, not because "make it a singleton" is
 * a default move -- see CODE-STYLE.md section 2 on reaching for a
 * pattern only once it names a real constraint.
 */
object ArkLogger {
    private const val DEFAULT_TAG = "ARKware"

    fun d(tag: String = DEFAULT_TAG, message: String) {
        Log.d(tag, message)
    }

    fun w(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
