package com.horizonarkstudio.arkware.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide logger. GoF Singleton: exactly one instance (a Kotlin
 * `object`), lazily bound to a Context via [init], reachable from
 * anywhere without threading a reference through every constructor.
 *
 * Two destinations:
 *  - Logcat, always, for every level -- same as `Log.*` would give you
 *    directly, just funneled through one call site.
 *  - [LOG_FILE_NAME], an internal-storage file (Context.filesDir,
 *    i.e. NOT external/shared storage -- no storage permission needed,
 *    and it's wiped on uninstall) that only ever receives warnings and
 *    errors. That's the file meant to be pulled off a device after
 *    something has gone wrong, e.g.:
 *      adb shell run-as com.horizonarkstudio.arkware cat files/--log-failed
 *  (run-as works on a debug build without root; a release build needs
 *  `adb shell` as a rooted/emulator user, or Android Studio's Device
 *  File Explorer, to reach app-internal storage.)
 *
 * Every public method is itself defensive: a logging call must never
 * be what crashes the app, so all file I/O here is wrapped in its own
 * try/catch/finally and silently falls back to Logcat-only on failure.
 */
object ArkLogger {

    private const val LOG_FILE_NAME = "--log-failed"
    private const val LOGCAT_TAG_PREFIX = "Ark"

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    /** Call once, as early as possible (Application.onCreate or MainActivity.onCreate). */
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            d("ArkLogger", "Logger initialized; failures will be appended to $LOG_FILE_NAME")
        } catch (t: Throwable) {
            // Even init() failing shouldn't take the app down with it.
            Log.e(tag("ArkLogger"), "init() failed", t)
        } finally {
            Log.i(tag("ArkLogger"), "init() completed")
        }
    }

    fun d(component: String, message: String) {
        Log.d(tag(component), message)
    }

    fun i(component: String, message: String) {
        Log.i(tag(component), message)
    }

    fun w(component: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag(component), message, throwable) else Log.w(tag(component), message)
        appendToFailureLog("WARN", component, message, throwable)
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag(component), message, throwable) else Log.e(tag(component), message)
        appendToFailureLog("ERROR", component, message, throwable)
    }

    /**
     * Wraps [block] with entry/exit/failure logging under [component]/
     * [operation], and rethrows whatever [block] threw after logging
     * it -- this is a helper for the try/catch/finally-everywhere
     * requirement, not a replacement for handling the error where it
     * actually matters.
     */
    inline fun <T> track(component: String, operation: String, block: () -> T): T {
        d(component, "$operation: start")
        try {
            val result = block()
            d(component, "$operation: success")
            return result
        } catch (t: Throwable) {
            e(component, "$operation: failed", t)
            throw t
        } finally {
            d(component, "$operation: end")
        }
    }

    private fun tag(component: String) = "$LOGCAT_TAG_PREFIX.$component"

    private fun appendToFailureLog(level: String, component: String, message: String, throwable: Throwable?) {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            FileWriter(file, /* append = */ true).use { writer ->
                writer.appendLine("${timestampFormat.format(Date())} [$level] $component: $message")
                throwable?.let { writer.appendLine(it.stackTraceToString()) }
            }
        } catch (t: Throwable) {
            // Deliberately Logcat-only here: if the file write itself
            // is failing, writing to the same file about that failure
            // would just loop.
            Log.e(tag("ArkLogger"), "Failed to append to $LOG_FILE_NAME", t)
        } finally {
            // No-op; present so the write path is symmetric with the
            // rest of the app's try/catch/finally logging convention.
        }
    }
}
