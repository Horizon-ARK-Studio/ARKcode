package com.arkware.ide.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.arkware.ide.logging.ArkLogger
import java.io.File
import java.io.IOException

/**
 * Owns the SAF (Storage Access Framework) concern for importing
 * libnode.so's missing shared-library dependencies (see
 * [com.arkware.ide.backend.IdeBackendService.REQUIRED_NATIVE_LIBS],
 * BUG-0002 in docs/bugs-caught/README.md) from an external source --
 * typically a real Termux install's `usr/lib`, reached via shared
 * storage -- into this app's own internal storage, as an alternative
 * to vendoring those `.so` files into `jniLibs/<abi>/` at build time.
 *
 * This works at all because `libnode.so` carries a `DT_RUNPATH` (not
 * a `DT_RPATH`) pointing at
 * `/data/data/com.termux/files/usr/lib` -- per the dynamic linker,
 * `DT_RUNPATH` is only consulted *after* `LD_LIBRARY_PATH`, so the
 * imported libraries never need to physically exist at that Termux
 * path. They only need to live somewhere
 * [com.arkware.ide.backend.IdeBackendService] puts on
 * `LD_LIBRARY_PATH` ahead of `DT_RUNPATH` being reached at all, which
 * [importDir] is. (A `DT_RPATH` binary would not work this way -- the
 * linker checks that *before* `LD_LIBRARY_PATH`, so an app-writable
 * override couldn't shadow it.)
 *
 * Same shape as [com.arkware.ide.workspace.WorkspaceManager]: this
 * class does not launch the picker itself --
 * `ActivityResultContracts.OpenDocumentTree()` has to be registered
 * by an Activity, not called from an arbitrary class (see
 * MainActivity.kt) -- it only owns what happens once a picked [Uri]
 * comes back: persisting it, and copying the libraries in.
 */
class TermuxLibImporter(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Where imported libraries land, and what
     * [com.arkware.ide.backend.IdeBackendService] adds to
     * `LD_LIBRARY_PATH` alongside `applicationInfo.nativeLibraryDir`.
     * Deliberately *not* `nativeLibraryDir` itself -- that directory
     * is populated only from `jniLibs/` at install time and is
     * read-only to the app at runtime.
     */
    val importDir: File
        get() = File(context.filesDir, "termux-lib")

    fun persistedTreeUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /**
     * Called once with the [Uri] the picker hands back. Read-only
     * grant -- this class only ever reads out of the picked tree,
     * never writes back into it, unlike
     * [com.arkware.ide.workspace.WorkspaceManager]'s two-way sync.
     */
    fun persistTreeUri(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            ArkLogger.w(
                message = "TermuxLibImporter: could not persist URI permission for $uri",
                throwable = e,
            )
        }
        prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
    }

    /**
     * Copies every shared-library file directly under the picked
     * tree into [importDir]. Flat, non-recursive: a real Termux
     * `usr/lib` -- what this is meant to point at -- is flat itself,
     * and staying flat avoids ever needing to reproduce a subtree
     * layout under `importDir`. Non-library entries (a stray
     * `README`, a subdirectory) are skipped rather than copied.
     * Best-effort per file, same reasoning as
     * [com.arkware.ide.workspace.WorkspaceManager.syncIn]: one
     * unreadable entry logs and is skipped, not aborts the whole
     * import. Call off the main thread.
     *
     * Returns the names actually copied, so the caller can report
     * back which of `REQUIRED_NATIVE_LIBS` this import satisfied.
     */
    fun importFrom(treeUri: Uri): List<String> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            ArkLogger.w(message = "TermuxLibImporter: could not resolve tree $treeUri")
            return emptyList()
        }
        importDir.mkdirs()
        val imported = mutableListOf<String>()
        for (child in root.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory || !isSharedLibraryName(name)) continue
            try {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(importDir, name).outputStream().use { output -> input.copyTo(output) }
                }
                imported += name
            } catch (e: IOException) {
                ArkLogger.w(message = "TermuxLibImporter: import failed for $name", throwable = e)
            }
        }
        return imported
    }

    /** Matches `libfoo.so`, `libfoo.so.1`, `libfoo.so.3`, etc. */
    private fun isSharedLibraryName(name: String): Boolean =
        SHARED_LIBRARY_NAME.matches(name)

    companion object {
        private const val PREFS_NAME = "ark_termux_lib_prefs"
        private const val KEY_TREE_URI = "termux_lib_tree_uri"
        private val SHARED_LIBRARY_NAME = Regex("""^lib[^/]+\.so(\.\d+)*$""")
    }
}
