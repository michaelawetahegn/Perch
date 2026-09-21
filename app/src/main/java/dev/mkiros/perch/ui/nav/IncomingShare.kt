package dev.mkiros.perch.ui.nav

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.mkiros.perch.model.Incoming

/**
 * Extracts the incoming value from an intent (PLAN-13 §0.8).
 *
 * Pure over the intent: no context needed. [displayName] is the URI's last
 * segment; the caller with a context replaces it with [displayNameOf].
 *
 * @return [Incoming.Link] if the intent is a SEND + text/plain with a URL,
 *   [Incoming.Document] if it is a SEND with EXTRA_STREAM or a VIEW with a PDF URI,
 *   null otherwise (MAIN launch, or no extractable value).
 */
fun incomingFrom(intent: Intent?): Incoming? {
    if (intent == null) return null

    val action = intent.action
    val type = intent.type

    return when (action) {
        Intent.ACTION_SEND -> {
            if (type == "text/plain") {
                // Extract the first http(s):// URL from the text
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val urlRegex = """https?://[^\s]+""".toRegex()
                val url = urlRegex.find(text)?.value
                if (url != null) Incoming.Link(url) else null
            } else if (type == "application/pdf") {
                // PDF stream was shared
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) Incoming.Document(uri, uri.lastPathSegment) else null
            } else {
                null
            }
        }
        // Opening a file with Perch. The manifest's filter only lets a PDF through, but
        // getType() is only a type the sender set, and a file manager may send the data alone.
        Intent.ACTION_VIEW -> intent.data?.let { Incoming.Document(it, it.lastPathSegment) }
        else -> null
    }
}

/**
 * The name the reader knows a file by — [OpenableColumns.DISPLAY_NAME] from its provider.
 * A picker's or a share's `content://` URI ends in a document id, not a file name, so its
 * last segment names nothing; null when the provider does not say.
 */
fun ContentResolver.displayNameOf(uri: Uri): String? = try {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: Exception) {
    // A provider that refuses the query (or a file:// URI) still leaves a readable file.
    null
}
