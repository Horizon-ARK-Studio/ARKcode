package com.arkware.ide.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.arkware.ide.logging.ArkLogger
import java.io.File
import java.io.IOException

/**
 * Owns exactly the SAF (Storage Access Framework) concern from
 * VSCODE-IDE-IMPLEMENTATION-PLAN.md section 6: letting the user pick
 * a workspace folder that lives outside this app's private sandbox,
 * persisting permission to it across restarts, and keeping the
 * actual Node process's working directory entirely inside
 * `filesDir/workspace/` -- the "(ii) pragmatic default" the plan
 * picks over a live DocumentFile/FUSE bridge, specifically so no
 * `noexec`/permission complications from section 4/0 leak into
 * workspace storage.
 *
 * This class does not launch the picker itself --
 * `ActivityResultContracts.OpenDocumentTree()` has to be registered
 * by an Activity, not called from an arbitrary class (see
 * MainActivity.kt) -- it only owns what happens once a [Uri] comes
 * back: persisting it, and running the copy-in/copy-out sync.
 */
class WorkspaceManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Where [IdeBackendService][com.arkware.ide.backend.IdeBackendService]
     * actually points the Node process -- always inside the app
     * sandbox, picked workspace or not, per section 6's reasoning:
     * this is what lets the backend run with zero extra permission
     * and no SAF/`DocumentFile` complexity in its own launch path.
     */
    val sandboxWorkspaceDir: File
        get() = File(context.filesDir, "workspace")

    fun persistedTreeUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /**
     * Called once with the [Uri] the picker hands back. The
     * document-provider-returned Uri already carries a read/write
     * grant for this session; `takePersistableUriPermission` is what
     * makes that grant survive past this process's lifetime, which is
     * the whole point of remembering it at all.
     */
    fun persistTreeUri(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            ArkLogger.w(
                message = "WorkspaceManager: could not persist URI permission for $uri",
                throwable = e,
            )
        }
        prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
    }

    /**
     * Copies the picked tree's contents into [sandboxWorkspaceDir].
     * Only overwrites a destination file when the source is newer or
     * the destination doesn't exist yet -- a plain wipe-and-recopy
     * would destroy anything the Node process has already written
     * into the sandbox copy since the last sync. Best-effort: logs
     * and skips individual files it can't read rather than aborting
     * the whole sync over one bad entry. Call off the main thread.
     */
    fun syncIn(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            ArkLogger.w(message = "WorkspaceManager: could not resolve tree $treeUri for sync-in")
            return
        }
        sandboxWorkspaceDir.mkdirs()
        copyDocumentTreeInto(root, sandboxWorkspaceDir)
    }

    /**
     * Copies [sandboxWorkspaceDir]'s contents back out to the picked
     * tree. Same newer-or-missing rule and same best-effort-per-file
     * behavior as [syncIn]. Deliberately does not delete anything on
     * the destination side that's missing from the sandbox copy -- a
     * delete-sync is a real way to lose a user's files over a bug in
     * this class, not worth the risk for an MVP. A file removed
     * inside the IDE currently has to be removed from the picked
     * folder by hand too. Call off the main thread.
     */
    fun syncOut(treeUri: Uri) {
        if (!sandboxWorkspaceDir.exists()) return
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            ArkLogger.w(message = "WorkspaceManager: could not resolve tree $treeUri for sync-out")
            return
        }
        copyFileTreeInto(sandboxWorkspaceDir, root)
    }

    // ---- DocumentFile (SAF tree) -> java.io.File (sandbox) --------------

    private fun copyDocumentTreeInto(srcDir: DocumentFile, destDir: File) {
        for (child in srcDir.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyDocumentTreeInto(child, File(destDir, name).apply { mkdirs() })
                continue
            }
            val childDest = File(destDir, name)
            if (childDest.exists() && childDest.lastModified() >= child.lastModified()) continue
            try {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                ArkLogger.w(message = "WorkspaceManager: sync-in failed for $name", throwable = e)
            }
        }
    }

    // ---- java.io.File (sandbox) -> DocumentFile (SAF tree) --------------

    private fun copyFileTreeInto(srcDir: File, destDir: DocumentFile) {
        for (child in srcDir.listFiles().orEmpty()) {
            if (child.isDirectory) {
                val childDest = destDir.findFile(child.name) ?: destDir.createDirectory(child.name)
                if (childDest == null) {
                    ArkLogger.w(message = "WorkspaceManager: could not create dir ${child.name} in tree")
                    continue
                }
                copyFileTreeInto(child, childDest)
                continue
            }
            val existing = destDir.findFile(child.name)
            if (existing != null && existing.lastModified() >= child.lastModified()) continue
            val target = existing ?: destDir.createFile(guessMimeType(child.name), child.name)
            if (target == null) {
                ArkLogger.w(message = "WorkspaceManager: could not create file ${child.name} in tree")
                continue
            }
            try {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                ArkLogger.w(message = "WorkspaceManager: sync-out failed for ${child.name}", throwable = e)
            }
        }
    }

    private fun guessMimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
            ?: "application/octet-stream"

    companion object {
        private const val PREFS_NAME = "ark_workspace_prefs"
        private const val KEY_TREE_URI = "workspace_tree_uri"
    }
}
