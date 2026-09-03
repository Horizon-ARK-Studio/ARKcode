package com.arkware.ide.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arkware.ide.R
import com.arkware.ide.logging.ArkLogger
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the local IDE backend's process lifecycle. Foreground
 * `Service`, promoted to foreground only once the backend is
 * confirmed actually listening (see [promoteToForeground]) -- an
 * unpromoted service that never reaches real activity is a
 * foreground-service violation waiting to happen, not a lifecycle
 * detail to skip.
 *
 * This is the real launch path from VSCODE-IDE-IMPLEMENTATION-PLAN.md
 * section 4.2, replacing the earlier Phase-1 placeholder (a bare
 * `ServerSocket` that only proved the port-handoff plumbing end to
 * end). It depends on two artifacts this repo does not fully vendor
 * yet, and [launchNode] fails loudly into [IdeBackendState.Failed]
 * when either is missing rather than silently falling back to a fake
 * server:
 *
 *  1. `libnode.so` under this ABI's `jniLibs/` -- the actual
 *     bionic-linked Node binary section 5(b) describes building from
 *     `vendor/termux-packages/nodejs-lts/`. That build needs a real
 *     NDK + host LLVM toolchain and, per that recipe's own `build.sh`
 *     comment, takes on the order of hours per architecture; it is
 *     not something any step in this repo runs today. Vendoring the
 *     *recipe* (already done, see that directory's own README) is
 *     sourcing only, not a build step.
 *  2. `code-server`'s server-side bundle (`out/node/entry.js` plus
 *     its `node_modules`) under `assets/code-server/`, extracted to
 *     `filesDir/code-server/` by [extractAssetsOnce] below.
 *     `scripts/vendor-code-server.sh` only fetches the
 *     platform-independent *workbench* frontend (`out/vs`,
 *     `out/media`, etc.) -- deliberately, per that script's own
 *     header, since the server side ships a linux-x64 `node-pty`
 *     addon that's useless on Android as-is (the Phase 4 risk the
 *     plan calls out separately). Until a follow-up vendors the
 *     server bundle too, [launchNode] will always hit the
 *     "entry.js missing" branch on-device.
 *
 * ## Why the stdout/stderr readers are plain `Thread`s, not coroutines
 *
 * `Process#getInputStream()`/`getErrorStream()` are blocking streams
 * with no cooperative cancellation point, same reasoning as this
 * class's earlier placeholder gave for `ServerSocket.accept()`: the
 * only real way to unblock a reader parked on `readLine()` is for the
 * process itself to exit or be destroyed, which [stopBackend] does
 * explicitly. A plain `Thread` makes that the honest mechanism
 * instead of dressing a non-cancellable blocking call up as
 * cancellable coroutine code.
 */
class IdeBackendService : Service() {

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)

    @Volatile
    private var nodeProcess: Process? = null
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null

    private val _state = MutableStateFlow<IdeBackendState>(IdeBackendState.Starting)
    val state: StateFlow<IdeBackendState> get() = _state

    inner class LocalBinder : Binder() {
        fun service(): IdeBackendService = this@IdeBackendService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workspaceDir = intent?.getStringExtra(EXTRA_WORKSPACE_DIR)
            ?: File(filesDir, "workspace").absolutePath
        startBackend(workspaceDir)
        // Not sticky: MainActivity always re-issues this Intent (with
        // a freshly re-synced workspace dir, see MainActivity.kt) on
        // every launch/recreation that needs the backend, so there's
        // no case where letting the system restart this Service with
        // a null Intent and no workspace dir would be useful.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopBackend()
        super.onDestroy()
    }

    // ---- lifecycle -----------------------------------------------------

    private fun startBackend(workspaceDir: String) {
        if (!running.compareAndSet(false, true)) return // already starting/running

        Thread({ launchNode(workspaceDir) }, "ide-backend-launch").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopBackend() {
        running.set(false)
        nodeProcess?.let { process ->
            process.destroy()
            val exited = try {
                process.waitFor(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!exited) {
                // Android does not guarantee a child process dies with
                // its parent Service -- an orphaned Node process
                // holding the loopback port across app restarts is
                // exactly the kind of bug that's invisible until a
                // second launch mysteriously can't bind (plan section
                // 4.5).
                ArkLogger.w(
                    message = "IdeBackendService: node process didn't exit within " +
                        "${SHUTDOWN_GRACE_MS}ms, forcing",
                )
                process.destroyForcibly()
            }
        }
        nodeProcess = null
        stdoutThread?.join(THREAD_JOIN_TIMEOUT_MS)
        stderrThread?.join(THREAD_JOIN_TIMEOUT_MS)
        stdoutThread = null
        stderrThread = null
    }

    /** See the class doc for the two artifacts this depends on. */
    private fun launchNode(workspaceDir: String) {
        val nodeBinary = File(applicationInfo.nativeLibraryDir, "libnode.so")
        if (!nodeBinary.exists()) {
            fail(
                "libnode.so not found under ${applicationInfo.nativeLibraryDir} -- build it " +
                    "from vendor/termux-packages/nodejs-lts/ (plan section 5b) and package it " +
                    "as jniLibs/<abi>/libnode.so before this can start for real",
            )
            return
        }

        val codeServerRoot = File(filesDir, "code-server")
        try {
            extractAssetsOnce(codeServerRoot)
        } catch (e: IOException) {
            fail("failed to extract bundled code-server assets: ${e.message}")
            return
        }

        val entryJs = File(codeServerRoot, "out/node/entry.js")
        if (!entryJs.exists()) {
            fail(
                "${entryJs.absolutePath} not found -- only the workbench frontend is vendored " +
                    "today (scripts/vendor-code-server.sh); the server-side out/node/ bundle " +
                    "still needs its own vendoring pass before this can launch for real",
            )
            return
        }

        File(workspaceDir).mkdirs()
        val userDataDir = File(codeServerRoot, "user-data").apply { mkdirs() }

        // --bind-addr 127.0.0.1:0 matters twice over: loopback keeps
        // the server unreachable from off-device, and port 0 avoids a
        // hardcoded port colliding with another app or a previous
        // instance that didn't clean up (plan section 4.2). --auth
        // none is defensible specifically because of loopback binding
        // (section 4.3) -- switch to --auth password with a random,
        // EncryptedSharedPreferences-stored password if defense in
        // depth against a co-installed malicious app is ever needed.
        val command = listOf(
            nodeBinary.absolutePath,
            entryJs.absolutePath,
            "--bind-addr", "127.0.0.1:0",
            "--auth", "none",
            "--user-data-dir", userDataDir.absolutePath,
            workspaceDir,
        )
        ArkLogger.d(message = "IdeBackendService: launching ${command.joinToString(" ")}")

        val process = try {
            ProcessBuilder(command)
                .directory(codeServerRoot)
                .start()
        } catch (e: IOException) {
            fail("failed to exec libnode.so: ${e.message}")
            return
        }
        nodeProcess = process

        stderrThread = Thread({ drainStderr(process) }, "ide-backend-stderr").apply {
            isDaemon = true
            start()
        }
        stdoutThread = Thread({ watchStdoutForPort(process) }, "ide-backend-stdout").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * code-server logs its bound port to stdout on startup -- this is
     * a best-effort line scan for a `127.0.0.1:<port>` substring, not
     * a strict protocol against a documented log format. If a future
     * code-server version changes its startup log line, this needs
     * updating alongside it (plan section 4.2).
     */
    private fun watchStdoutForPort(process: Process) {
        var reportedReady = false
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String? = null
                while (running.get() && reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    ArkLogger.d(tag = NODE_LOG_TAG, message = text)
                    if (!reportedReady) {
                        val port = PORT_LOG_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
                        if (port != null) {
                            reportedReady = true
                            promoteToForeground(port)
                            _state.value = IdeBackendState.Ready(port)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (running.get()) {
                ArkLogger.w(
                    message = "IdeBackendService: stdout stream closed unexpectedly",
                    throwable = e,
                )
            }
        }
        if (!reportedReady && running.get()) {
            fail("node process exited before ever reporting a bound port")
        }
    }

    private fun drainStderr(process: Process) {
        try {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    ArkLogger.w(tag = NODE_LOG_TAG, message = line ?: "")
                }
            }
        } catch (e: IOException) {
            // Expected once stopBackend() destroys the process out from
            // under this reader -- not a real failure on its own.
        }
    }

    /**
     * Copies `assets/code-server/` into [destRoot] the first time this
     * runs (guarded by a marker file, not a version check -- a stale
     * extraction is a follow-up concern once this ships a real
     * versioned bundle, see plan section 7's Phase 6 CI note). Plain
     * file copy, not execute: `noexec` (section 0.1) only blocks
     * execution, not read/copy, so extracting the JS/JSON assets
     * themselves is unaffected by it -- only [nodeBinary] needed the
     * `jniLibs` treatment.
     */
    private fun extractAssetsOnce(destRoot: File) {
        val marker = File(destRoot, EXTRACTED_MARKER)
        if (marker.exists()) return
        destRoot.deleteRecursively()
        destRoot.mkdirs()
        copyAssetTree(ASSET_ROOT, destRoot)
        marker.writeText(System.currentTimeMillis().toString())
    }

    private fun copyAssetTree(assetDir: String, destDir: File) {
        destDir.mkdirs()
        for (entry in assets.list(assetDir).orEmpty()) {
            val childAssetPath = "$assetDir/$entry"
            val childDest = File(destDir, entry)
            val childEntries = assets.list(childAssetPath)
            if (childEntries.isNullOrEmpty()) {
                // Leaf file -- AssetManager.list() also returns empty
                // for a genuinely empty directory, but the vendored
                // code-server tree never produces one, so treating
                // "no children" as "it's a file" is safe here.
                assets.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetTree(childAssetPath, childDest)
            }
        }
    }

    private fun fail(reason: String) {
        ArkLogger.e(message = "IdeBackendService: $reason")
        _state.value = IdeBackendState.Failed(reason)
        running.set(false)
    }

    private fun promoteToForeground(port: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.ide_backend_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.ide_backend_notification_title))
            .setContentText(getString(R.string.ide_backend_notification_text, port))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        /** Set by MainActivity to the sandbox-synced workspace dir (see WorkspaceManager). */
        const val EXTRA_WORKSPACE_DIR = "com.arkware.ide.extra.WORKSPACE_DIR"

        private const val ASSET_ROOT = "code-server"
        private const val EXTRACTED_MARKER = ".extracted"
        private const val SHUTDOWN_GRACE_MS = 3_000L
        private const val THREAD_JOIN_TIMEOUT_MS = 2_000L
        private const val NOTIFICATION_CHANNEL_ID = "ide_backend"
        private const val NOTIFICATION_ID = 1001
        private const val NODE_LOG_TAG = "IdeBackendService.node"
        private val PORT_LOG_PATTERN = Regex("""127\.0\.0\.1:(\d+)""")
    }
}
