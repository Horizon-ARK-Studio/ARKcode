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
import com.arkware.ide.termux.TermuxLibImporter
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
 * `Service`: [promoteToForegroundStarting] satisfies Android's
 * startForeground()-promptly contract immediately on launch, and
 * [promoteToForeground] later swaps the notification over to the
 * real port once the backend is confirmed listening -- see
 * BUG-0001 in docs/bugs-caught/README.md for why the immediate call
 * exists at all (a Service that only ever promotes on success gets
 * killed by the OS on any failure path instead).
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
 *  Even once both artifacts are vendored, [REQUIRED_NATIVE_LIBS]
 *  covers a third gap found while chasing BUG-0002: `libnode.so`
 *  itself dynamically links against 8 shared libraries no stock
 *  Android device ships and this repo doesn't vendor yet either
 *  (`readelf -d jniLibs/<abi>/libnode.so`'s NEEDED entries) --
 *  [launchNode] checks for them explicitly and fails with their exact
 *  names rather than letting the dynamic linker's abort surface only
 *  as an unexplained "exited before reporting a port". As of this
 *  change that check (and the `LD_LIBRARY_PATH` [launchNode] builds)
 *  looks in two places, not just `nativeLibraryDir`:
 *  [TermuxLibImporter.importDir] is searched too, so libraries the
 *  user has imported at runtime via that class's SAF picker (see
 *  MainActivity.kt) satisfy this requirement exactly as well as ones
 *  vendored into `jniLibs/<abi>/` at build time would.
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

    // Bounded ring of the child process's most recent stderr lines
    // (see BUG-0002 in docs/bugs-caught/README.md) -- populated by
    // drainStderr(), read by launchNode() when watchStdoutForPort()
    // ends without ever seeing a port, so the *actual* native failure
    // (e.g. a dynamic-linker "library not found" abort) reaches
    // IdeBackendState.Failed's reason instead of only Logcat.
    private val stderrTail = ArrayDeque<String>()
    private val stderrTailLock = Any()

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

        // Explicit reset, not just relying on the initial MutableStateFlow
        // value: on a retry after a Failed state, StateFlow only emits
        // *distinct* values, so if launchNode() below fails again with
        // the exact same reason string, IdeBackendState.Failed(reason)
        // would be equal to what's already there and never re-emit --
        // MainActivity's collector would never fire and the WebView
        // would silently stay on the failed screen instead of showing
        // "Starting..." during the retry attempt.
        _state.value = IdeBackendState.Starting

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
        // Must happen before anything below that can return early via
        // fail() -- Android requires a Service started via
        // startForegroundService() (see MainActivity's
        // ContextCompat.startForegroundService call) to call
        // Service.startForeground() promptly (a few seconds) or the
        // OS kills the whole process with
        // ForegroundServiceDidNotStartInTimeException. That deadline
        // does not care *why* startup is slow or whether it ultimately
        // fails -- it only cares that startForeground() was called at
        // all, so this has to run unconditionally up front, not only
        // once a port is confirmed in watchStdoutForPort(). See
        // BUG-0001 in docs/bugs-caught/README.md.
        promoteToForegroundStarting()

        val nodeBinary = File(applicationInfo.nativeLibraryDir, "libnode.so")
        if (!nodeBinary.exists()) {
            fail(
                "libnode.so not found under ${applicationInfo.nativeLibraryDir} -- build it " +
                    "from vendor/termux-packages/nodejs-lts/ (plan section 5b) and package it " +
                    "as jniLibs/<abi>/libnode.so before this can start for real",
            )
            return
        }

        // See BUG-0002 in docs/bugs-caught/README.md: libnode.so is a
        // Termux-built binary dynamically linked against these shared
        // libraries (verified via `readelf -d libnode.so`'s NEEDED
        // entries), none of which exist on stock Android. Two places
        // are searched for them, mirroring the two entries put on
        // LD_LIBRARY_PATH below: jniLibs/<abi>/ (vendored at build
        // time) and TermuxLibImporter.importDir (imported at runtime
        // via the SAF picker in MainActivity.kt, e.g. pointed at a
        // real Termux install's usr/lib). Checking for them
        // explicitly, before ever attempting exec, turns a cryptic
        // dynamic-linker abort inside the child process (only visible
        // today via drainStderr()'s Logcat output, never surfaced to
        // IdeBackendState.Failed) into a specific, actionable failure
        // reason naming exactly which files are missing and where
        // this looked for them. libc.so/libm.so/libdl.so are
        // deliberately excluded from this list -- those are
        // Android's own bionic libc, always present, not something
        // this app vendors or imports.
        val nativeLibSearchDirs = listOf(
            File(applicationInfo.nativeLibraryDir),
            TermuxLibImporter(this).importDir,
        )
        val missingDeps = REQUIRED_NATIVE_LIBS.filterNot { name ->
            nativeLibSearchDirs.any { dir -> File(dir, name).exists() }
        }
        if (missingDeps.isNotEmpty()) {
            fail(
                "libnode.so is missing ${missingDeps.size} required shared librar" +
                    (if (missingDeps.size == 1) "y" else "ies") +
                    ": ${missingDeps.joinToString(", ")} -- not found under any of " +
                    "${nativeLibSearchDirs.joinToString(", ") { it.absolutePath }}. Either " +
                    "vendor them alongside libnode.so as jniLibs/<abi>/<name> (same Termux " +
                    "nodejs-lts build as libnode.so itself, plan section 5b), or import them " +
                    "at runtime via the folder picker (e.g. pointed at a real Termux install's " +
                    "usr/lib) before this can start for real",
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
                .apply {
                    // libnode.so's baked-in DT_RUNPATH points at
                    // /data/data/com.termux/files/usr/lib, which does not
                    // exist in this app's sandbox. The dynamic linker
                    // only consults DT_RUNPATH *after* LD_LIBRARY_PATH,
                    // so pointing LD_LIBRARY_PATH at nativeLibSearchDirs
                    // instead is what lets it find REQUIRED_NATIVE_LIBS
                    // there -- in either this app's own nativeLibraryDir
                    // or TermuxLibImporter's runtime-imported dir --
                    // now that the check above has already confirmed
                    // they're actually present in at least one of them.
                    environment()["LD_LIBRARY_PATH"] =
                        nativeLibSearchDirs.joinToString(":") { it.absolutePath }
                }
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
            // Give drainStderr() a brief window to finish draining
            // whatever the child wrote before it exited -- both
            // streams close around the same time the process dies,
            // but there's no ordering guarantee between them.
            stderrThread?.join(STDERR_DRAIN_JOIN_MS)
            val tail = synchronized(stderrTailLock) { stderrTail.joinToString("\n") }
            val reason = if (tail.isBlank()) {
                "node process exited before ever reporting a bound port"
            } else {
                "node process exited before ever reporting a bound port; last stderr output:\n$tail"
            }
            fail(reason)
        }
    }

    private fun drainStderr(process: Process) {
        try {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: ""
                    ArkLogger.w(tag = NODE_LOG_TAG, message = text)
                    synchronized(stderrTailLock) {
                        stderrTail.addLast(text)
                        while (stderrTail.size > STDERR_TAIL_MAX_LINES) stderrTail.removeFirst()
                    }
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
        // The startForeground() promise from promoteToForegroundStarting()
        // is already satisfied by this point, so it's safe to drop back
        // out of the foreground state -- there's nothing left running
        // worth keeping a persistent notification up for.
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    /**
     * Satisfies Android's startForeground()-promptly contract before
     * any of [launchNode]'s failure-prone steps run. Uses an
     * "indeterminate" notification text since at this point it's not
     * yet known whether startup will succeed; [promoteToForeground]
     * below replaces it with the real port once one exists.
     */
    private fun promoteToForegroundStarting() {
        ensureNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.ide_backend_notification_title))
            .setContentText(getString(R.string.ide_backend_notification_starting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun promoteToForeground(port: Int) {
        ensureNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.ide_backend_notification_title))
            .setContentText(getString(R.string.ide_backend_notification_text, port))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        // Already in the foreground state from promoteToForegroundStarting();
        // calling startForeground() again just swaps the notification
        // content over to the real, port-bearing one.
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
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
    }

    companion object {
        /** Set by MainActivity to the sandbox-synced workspace dir (see WorkspaceManager). */
        const val EXTRA_WORKSPACE_DIR = "com.arkware.ide.extra.WORKSPACE_DIR"

        private const val ASSET_ROOT = "code-server"
        private const val EXTRACTED_MARKER = ".extracted"
        private const val SHUTDOWN_GRACE_MS = 3_000L
        private const val THREAD_JOIN_TIMEOUT_MS = 2_000L
        private const val STDERR_DRAIN_JOIN_MS = 500L
        private const val STDERR_TAIL_MAX_LINES = 20
        private const val NOTIFICATION_CHANNEL_ID = "ide_backend"
        private const val NOTIFICATION_ID = 1001
        private const val NODE_LOG_TAG = "IdeBackendService.node"
        private val PORT_LOG_PATTERN = Regex("""127\.0\.0\.1:(\d+)""")

        /**
         * libnode.so's NEEDED entries (`readelf -d jniLibs/arm64-v8a/libnode.so`),
         * minus libc.so/libm.so/libdl.so -- those three are Android's
         * own bionic libc, always present on-device, never vendored by
         * this app. See BUG-0002 in docs/bugs-caught/README.md.
         */
        private val REQUIRED_NATIVE_LIBS = listOf(
            "libz.so.1",
            "libcares.so",
            "libsqlite3.so",
            "libcrypto.so.3",
            "libssl.so.3",
            "libicui18n.so.78",
            "libicuuc.so.78",
            "libc++_shared.so",
        )
    }
}
