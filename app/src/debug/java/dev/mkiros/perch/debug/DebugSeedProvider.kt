package dev.mkiros.perch.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dev.mkiros.perch.PerchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs [DebugSeeder] once at startup, in the debug build only.
 *
 * A content provider is the hook because it is the only startup callback that a source
 * set can add on its own: the provider is declared in `src/debug/AndroidManifest.xml` and
 * merged in for debug builds, so `PerchApp` — which lives in `main` and ships in the
 * release APK — needs no seeding branch, no flag, and no knowledge that any of this
 * exists. Nothing here answers queries; the provider is a startup hook wearing a
 * provider's interface.
 */
class DebugSeedProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? PerchApp ?: return true
        // Providers are created before `Application.onCreate`, and this opens Room and
        // parses ~760 KB of XML, so it cannot happen on the main thread. The first frame
        // may therefore show the empty state for a moment — on a debug build's very first
        // launch only, which is a better trade than blocking every launch.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching {
                DebugSeeder(app.assets, app.container.feeds, app.container.clock).seedIfEmpty()
            }.onFailure { Log.w("DebugSeedProvider", "seeding failed", it) }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
