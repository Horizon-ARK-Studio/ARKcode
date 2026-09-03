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
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the local IDE backend's process/socket lifecycle. Foreground
 * `Service`, same shape ARKtube's `MediaPlaybackService` proved for
 * exactly this reason (see VSCODE-IDE-IMPLEMENTATION-PLAN.md section
 * 2): promoted to foreground only once the backend is confirmed
 * actually listening, not at `onCreate()` -- an unpromoted service
 * that never reaches real activity is a foreground-service violation
 * waiting to happen, not a lifecycle detail to skip.
 *
 * Correction against the plan document as written: section 2 cites
 * `MediaPlaybackService.kt` as existing, reusable prior art in this
 * repo. As of this commit `android-project/` has no such file --
 * ARKware is still Stage 0 (docs only) on the `app` branch. This
 * class follows the *shape* the plan describes from first principles
 * (foreground-only-once-real, explicit teardown in `onDestroy()`, a
 * bound-service port handoff) rather than copying code that isn't
 * actually present to copy.
 *
 * ## Why an accept loop on its own thread, not a coroutine on
 * `Dispatchers.IO`
 *
 * `ServerSocket.accept()` is a blocking call with no cooperative
 * cancellation point of its own -- a coroutine parked on it cannot be
 * cancelled by cancelling its job; the only way to unblock it is to
 * close the socket out from under it, which is exactly what
 * `onDestroy()` below does. Given that's the actual cancellation
 * mechanism regardless of what parks on `accept()`, a plain
 * dedicated `Thread` makes that mechanism explicit instead of
 * dressing a non-cancellable blocking call up as cancellable
 * coroutine code, which is more likely to mislead a future reader
 * than a raw `Thread` + `AtomicBoolean` guard is.
 */
class IdeBackendService : Service() {

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    private val _state = MutableStateFlow<IdeBackendState>(IdeBackendState.Starting)
    val state: StateFlow<IdeBackendState> get() = _state

    inner class LocalBinder : Binder() {
        fun service(): IdeBackendService = this@IdeBackendService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startBackend()
    }

    override fun onDestroy() {
        stopBackend()
        super.onDestroy()
    }

    // ---- lifecycle -----------------------------------------------------

    private fun startBackend() {
        if (!running.compareAndSet(false, true)) return // already starting/running

        serverThread = Thread({ runServer() }, "ide-backend-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopBackend() {
        running.set(false)
        // Closing the socket is what actually unblocks accept() below --
        // see the class doc's "why a plain Thread" note. Android does
        // not guarantee any child process this eventually spawns (once
        // this becomes a real ProcessBuilder exec, per the class doc)
        // dies with this Service; Phase 3 must additionally call
        // Process.destroy()/destroyForcibly() here once there is a real
        // child process to hold a reference to (see plan section 4.5) --
        // an orphaned process holding the loopback port across restarts
        // is exactly the invisible-until-second-launch bug that section
        // warns about, and this placeholder has no such process yet.
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            ArkLogger.w(message = "IdeBackendService: error closing server socket", throwable = e)
        }
        serverThread?.join(SHUTDOWN_JOIN_TIMEOUT_MS)
        serverThread = null
        serverSocket = null
    }

    /**
     * Placeholder backend body (Phase 1). Binds 127.0.0.1:0 -- port 0
     * so the OS picks a free port, same reasoning the plan gives for
     * the eventual real `code-server` launch in section 4.2: a
     * hardcoded port risks colliding with another app or a previous
     * instance that didn't clean up. Responds to every connection with
     * a static HTTP 200 placeholder page; this is deliberately not
     * yet the real `code-server`/Node process (see Phase 2/3 in
     * section 7) -- this phase exists to prove the port-handoff
     * plumbing (`ServerSocket` -> [_state] -> bound-service callback
     * -> `MainActivity` navigating the WebView) end-to-end before any
     * native-binary packaging risk (section 4.1, 5) is introduced.
     */
    private fun runServer() {
        val socket = try {
            ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        } catch (e: IOException) {
            ArkLogger.e(message = "IdeBackendService: failed to bind loopback socket", throwable = e)
            _state.value = IdeBackendState.Failed(e.message ?: "bind failed")
            running.set(false)
            return
        }

        serverSocket = socket
        val port = socket.localPort
        ArkLogger.d(message = "IdeBackendService: listening on 127.0.0.1:$port")
        promoteToForeground(port)
        _state.value = IdeBackendState.Ready(port)

        while (running.get()) {
            val client: Socket = try {
                socket.accept()
            } catch (e: IOException) {
                // Expected on shutdown: stopBackend() closes the socket,
                // which unblocks accept() with exactly this exception.
                // Only treat it as a real failure if we weren't asked
                // to stop.
                if (running.get()) {
                    ArkLogger.e(message = "IdeBackendService: accept() failed", throwable = e)
                }
                break
            }
            handleClient(client)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { s ->
                // Placeholder response only -- not a real HTTP parser.
                // Real code-server (Phase 3) is the actual HTTP server;
                // this phase only needs *a* response so MainActivity's
                // WebView navigation can be verified against a live
                // socket.
                val body = PLACEHOLDER_BODY.toByteArray(Charsets.UTF_8)
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                BufferedOutputStream(s.getOutputStream()).use { out ->
                    out.write(response.toByteArray(Charsets.US_ASCII))
                    out.write(body)
                    out.flush()
                }
            }
        } catch (e: IOException) {
            ArkLogger.w(message = "IdeBackendService: error serving client", throwable = e)
        }
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
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val BACKLOG = 8
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 2_000L
        private const val NOTIFICATION_CHANNEL_ID = "ide_backend"
        private const val NOTIFICATION_ID = 1001
        private const val PLACEHOLDER_BODY =
            "<!doctype html><title>ARKware IDE</title>" +
                "<p>Placeholder backend reachable -- port-handoff plumbing OK.</p>"
    }
}
